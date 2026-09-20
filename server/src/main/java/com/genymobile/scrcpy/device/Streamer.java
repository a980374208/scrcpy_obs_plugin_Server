package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.audio.AudioCodec;
import com.genymobile.scrcpy.util.Codec;
import com.genymobile.scrcpy.util.IO;

import android.media.MediaCodec;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class Streamer {

    private static final long PACKET_FLAG_CONFIG = 1L << 63;
    private static final long PACKET_FLAG_KEY_FRAME = 1L << 62;

    private final Codec codec;
    private final boolean sendCodecMeta;
    private final boolean sendFrameMeta;
    private final AudioStreamState audioStreamState;
    private final ByteWriter writer;

    private final ByteBuffer headerBuffer = ByteBuffer.allocate(12);

    interface ByteWriter {
        void write(ByteBuffer buffer) throws IOException;
    }

    public static final class AudioStreamState {
        private enum Phase {
            CODEC_ID,
            PACKETS,
            DISABLED,
        }

        private Phase phase = Phase.CODEC_ID;
    }

    public Streamer(FileDescriptor fd, Codec codec, boolean sendCodecMeta, boolean sendFrameMeta) {
        this(codec, sendCodecMeta, sendFrameMeta, null, buffer -> IO.writeFully(fd, buffer));
    }

    public Streamer(FileDescriptor fd, Codec codec, boolean sendCodecMeta, boolean sendFrameMeta, AudioStreamState audioStreamState) {
        this(codec, sendCodecMeta, sendFrameMeta, audioStreamState, buffer -> IO.writeFully(fd, buffer));
    }

    Streamer(Codec codec, boolean sendCodecMeta, boolean sendFrameMeta, AudioStreamState audioStreamState, ByteWriter writer) {
        this.codec = codec;
        this.sendCodecMeta = sendCodecMeta;
        this.sendFrameMeta = sendFrameMeta;
        this.audioStreamState = audioStreamState;
        this.writer = writer;
    }

    public Codec getCodec() {
        return codec;
    }

    public void writeAudioHeader() throws IOException {
        if (audioStreamState == null) {
            throw new IllegalStateException("Audio stream state is not configured");
        }

        synchronized (audioStreamState) {
            if (audioStreamState.phase == AudioStreamState.Phase.DISABLED) {
                throw new IOException("Audio stream was disabled before packet streaming started");
            }
            if (audioStreamState.phase == AudioStreamState.Phase.PACKETS) {
                return;
            }
            if (sendCodecMeta) {
                ByteBuffer buffer = ByteBuffer.allocate(4);
                buffer.putInt(codec.getId());
                buffer.flip();
                writer.write(buffer);
            }
            audioStreamState.phase = AudioStreamState.Phase.PACKETS;
        }
    }

    public void writeVideoHeader(Size videoSize) throws IOException {
        if (sendCodecMeta) {
            ByteBuffer buffer = ByteBuffer.allocate(12);
            buffer.putInt(codec.getId());
            buffer.putInt(videoSize.getWidth());
            buffer.putInt(videoSize.getHeight());
            buffer.flip();
            writer.write(buffer);
        }
    }

    public void writeDisableStream(boolean error) throws IOException {
        tryWriteDisableStream(error);
    }

    boolean tryWriteDisableStream(boolean error) throws IOException {
        if (audioStreamState == null) {
            throw new IllegalStateException("Audio stream state is not configured");
        }

        // Writing a specific code as codec-id means that the device disables the stream
        //   code 0: it explicitly disables the stream (because it could not capture audio), scrcpy should continue mirroring video only
        //   code 1: a configuration error occurred, scrcpy must be stopped
        // These codes are only valid before the stream enters the packet-framing phase.
        synchronized (audioStreamState) {
            if (!sendCodecMeta || audioStreamState.phase != AudioStreamState.Phase.CODEC_ID) {
                return false;
            }
            ByteBuffer buffer = ByteBuffer.allocate(4);
            buffer.putInt(error ? 1 : 0);
            buffer.flip();
            writer.write(buffer);
            audioStreamState.phase = AudioStreamState.Phase.DISABLED;
            return true;
        }
    }

    public void writePacket(ByteBuffer buffer, long pts, boolean config, boolean keyFrame) throws IOException {
        if (config) {
            if (codec == AudioCodec.OPUS) {
                fixOpusConfigPacket(buffer);
            } else if (codec == AudioCodec.FLAC) {
                fixFlacConfigPacket(buffer);
            }
        }

        if (audioStreamState != null) {
            synchronized (audioStreamState) {
                if (audioStreamState.phase != AudioStreamState.Phase.PACKETS) {
                    throw new IOException("Audio packet stream has not started");
                }
                writePacketInternal(buffer, pts, config, keyFrame);
            }
        } else {
            writePacketInternal(buffer, pts, config, keyFrame);
        }
    }

    private void writePacketInternal(ByteBuffer buffer, long pts, boolean config, boolean keyFrame) throws IOException {
        if (sendFrameMeta) {
            writeFrameMeta(buffer.remaining(), pts, config, keyFrame);
        }

        writer.write(buffer);
    }

    public void writePacket(ByteBuffer codecBuffer, MediaCodec.BufferInfo bufferInfo) throws IOException {
        long pts = bufferInfo.presentationTimeUs;
        boolean config = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
        boolean keyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
        writePacket(codecBuffer, pts, config, keyFrame);
    }

    private void writeFrameMeta(int packetSize, long pts, boolean config, boolean keyFrame) throws IOException {
        headerBuffer.clear();

        long ptsAndFlags;
        if (config) {
            ptsAndFlags = PACKET_FLAG_CONFIG; // non-media data packet
        } else {
            ptsAndFlags = pts;
            if (keyFrame) {
                ptsAndFlags |= PACKET_FLAG_KEY_FRAME;
            }
        }

        headerBuffer.putLong(ptsAndFlags);
        headerBuffer.putInt(packetSize);
        headerBuffer.flip();
        writer.write(headerBuffer);
    }

    private static void fixOpusConfigPacket(ByteBuffer buffer) throws IOException {
        // Here is an example of the config packet received for an OPUS stream:
        //
        // 00000000  41 4f 50 55 53 48 44 52  13 00 00 00 00 00 00 00  |AOPUSHDR........|
        // -------------- BELOW IS THE PART WE MUST PUT AS EXTRADATA  -------------------
        // 00000010  4f 70 75 73 48 65 61 64  01 01 38 01 80 bb 00 00  |OpusHead..8.....|
        // 00000020  00 00 00                                          |...             |
        // ------------------------------------------------------------------------------
        // 00000020           41 4f 50 55 53  44 4c 59 08 00 00 00 00  |   AOPUSDLY.....|
        // 00000030  00 00 00 a0 2e 63 00 00  00 00 00 41 4f 50 55 53  |.....c.....AOPUS|
        // 00000040  50 52 4c 08 00 00 00 00  00 00 00 00 b4 c4 04 00  |PRL.............|
        // 00000050  00 00 00                                          |...|
        //
        // Each "section" is prefixed by a 64-bit ID and a 64-bit length.
        //
        // <https://developer.android.com/reference/android/media/MediaCodec#CSD>

        if (buffer.remaining() < 16) {
            throw new IOException("Not enough data in OPUS config packet");
        }

        final byte[] opusHeaderId = {'A', 'O', 'P', 'U', 'S', 'H', 'D', 'R'};
        byte[] idBuffer = new byte[8];
        buffer.get(idBuffer);
        if (!Arrays.equals(idBuffer, opusHeaderId)) {
            throw new IOException("OPUS header not found");
        }

        // The size is in native byte-order
        long sizeLong = buffer.getLong();
        if (sizeLong < 0 || sizeLong >= 0x7FFFFFFF) {
            throw new IOException("Invalid block size in OPUS header: " + sizeLong);
        }

        int size = (int) sizeLong;
        if (buffer.remaining() < size) {
            throw new IOException("Not enough data in OPUS header (invalid size: " + size + ")");
        }

        // Set the buffer to point to the OPUS header slice
        buffer.limit(buffer.position() + size);
    }

    private static void fixFlacConfigPacket(ByteBuffer buffer) throws IOException {
        // 00000000  66 4c 61 43 00 00 00 22                           |fLaC..."        |
        // -------------- BELOW IS THE PART WE MUST PUT AS EXTRADATA  -------------------
        // 00000000                           10 00 10 00 00 00 00 00  |        ........|
        // 00000010  00 00 0b b8 02 f0 00 00  00 00 00 00 00 00 00 00  |................|
        // 00000020  00 00 00 00 00 00 00 00  00 00                    |..........      |
        // ------------------------------------------------------------------------------
        // 00000020                                 84 00 00 28 20 00  |          ...( .|
        // 00000030  00 00 72 65 66 65 72 65  6e 63 65 20 6c 69 62 46  |..reference libF|
        // 00000040  4c 41 43 20 31 2e 33 2e  32 20 32 30 32 32 31 30  |LAC 1.3.2 202210|
        // 00000050  32 32 00 00 00 00                                 |22....|
        //
        // <https://developer.android.com/reference/android/media/MediaCodec#CSD>

        if (buffer.remaining() < 8) {
            throw new IOException("Not enough data in FLAC config packet");
        }

        final byte[] flacHeaderId = {'f', 'L', 'a', 'C'};
        byte[] idBuffer = new byte[4];
        buffer.get(idBuffer);
        if (!Arrays.equals(idBuffer, flacHeaderId)) {
            throw new IOException("FLAC header not found");
        }

        // The size is in big-endian
        buffer.order(ByteOrder.BIG_ENDIAN);

        int size = buffer.getInt();
        if (buffer.remaining() < size) {
            throw new IOException("Not enough data in FLAC header (invalid size: " + size + ")");
        }

        // Set the buffer to point to the FLAC header slice
        buffer.limit(buffer.position() + size);
    }
}

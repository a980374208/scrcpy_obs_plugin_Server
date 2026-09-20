package com.genymobile.scrcpy.device;

import com.genymobile.scrcpy.audio.AudioCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Assert;
import org.junit.Test;

public class StreamerAudioProtocolTest {

    private static Streamer createStreamer(Streamer.AudioStreamState state, boolean sendCodecMeta, ByteArrayOutputStream output) {
        return new Streamer(AudioCodec.OPUS, sendCodecMeta, true, state, buffer -> {
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            output.write(data);
        });
    }

    private static void assertPacket(ByteBuffer wire, long pts, byte[] payload) {
        Assert.assertEquals(pts, wire.getLong());
        Assert.assertEquals(payload.length, wire.getInt());
        byte[] actual = new byte[payload.length];
        wire.get(actual);
        Assert.assertArrayEquals(payload, actual);
    }

    private static void assertWriteFails(Streamer streamer) {
        try {
            streamer.writeAudioHeader();
            Assert.fail("Header write must fail after an initial disable code");
        } catch (IOException expected) {
            // expected
        }

        try {
            streamer.writePacket(ByteBuffer.wrap(new byte[] {1}), 1, false, false);
            Assert.fail("Packet write must fail after an initial disable code");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test(timeout = 3000)
    public void initialDisableCodesRemainCodecStageOnly() throws Exception {
        ByteArrayOutputStream disabledOutput = new ByteArrayOutputStream();
        Streamer.AudioStreamState disabledState = new Streamer.AudioStreamState();
        Streamer disabled = createStreamer(disabledState, true, disabledOutput);

        Assert.assertTrue(disabled.tryWriteDisableStream(false));
        Assert.assertFalse(disabled.tryWriteDisableStream(true));
        Assert.assertArrayEquals(new byte[] {0, 0, 0, 0}, disabledOutput.toByteArray());
        assertWriteFails(disabled);

        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
        Streamer configurationError = createStreamer(new Streamer.AudioStreamState(), true, errorOutput);
        Assert.assertTrue(configurationError.tryWriteDisableStream(true));
        Assert.assertArrayEquals(new byte[] {0, 0, 0, 1}, errorOutput.toByteArray());
    }

    @Test(timeout = 3000)
    public void restartFailureDoesNotCorruptPacketFraming() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Streamer.AudioStreamState state = new Streamer.AudioStreamState();

        Streamer first = createStreamer(state, true, output);
        first.writeAudioHeader();
        first.writePacket(ByteBuffer.wrap(new byte[] {0x11, 0x22}), 7, false, false);

        Streamer failedRestart = createStreamer(state, true, output);
        Assert.assertFalse(failedRestart.tryWriteDisableStream(false));

        Streamer recovered = createStreamer(state, true, output);
        recovered.writeAudioHeader();
        recovered.writePacket(ByteBuffer.wrap(new byte[] {0x33}), 8, false, false);

        ByteBuffer wire = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.BIG_ENDIAN);
        Assert.assertEquals(AudioCodec.OPUS.getId(), wire.getInt());
        assertPacket(wire, 7, new byte[] {0x11, 0x22});
        assertPacket(wire, 8, new byte[] {0x33});
        Assert.assertFalse("Parser must end exactly at EOF", wire.hasRemaining());
    }

    @Test(timeout = 3000)
    public void streamWithoutCodecMetaNeverWritesDisableCode() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Streamer.AudioStreamState state = new Streamer.AudioStreamState();
        Streamer streamer = createStreamer(state, false, output);

        streamer.writeAudioHeader();
        Assert.assertFalse(streamer.tryWriteDisableStream(true));
        streamer.writePacket(ByteBuffer.wrap(new byte[] {0x55}), 9, false, false);

        ByteBuffer wire = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.BIG_ENDIAN);
        assertPacket(wire, 9, new byte[] {0x55});
        Assert.assertFalse("Parser must end exactly at EOF", wire.hasRemaining());
    }
}

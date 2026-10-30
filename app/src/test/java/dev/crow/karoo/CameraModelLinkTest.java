package dev.crow.karoo;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class CameraModelLinkTest {
    private CameraLink link() {
        return new CameraLink(RuntimeEnvironment.getApplication(), new CameraLink.Listener() {
            public void changed() {}
            public void found(BluetoothDevice device, String name) {}
        });
    }

    private void approve(CameraLink link, long deviceId, int result) throws Exception {
        byte[] payload = new byte[33];
        Protocol.put(payload, 0, deviceId, 4);
        payload[26] = 2;
        Protocol.put(payload, 27, result, 2);
        Protocol.Frame frame = new Protocol.Decoder().accept(Protocol.encode(2, 71, 0, 0x19, payload)).get(0);
        Method receive = CameraLink.class.getDeclaredMethod("receive", Protocol.Frame.class);
        receive.setAccessible(true);
        receive.invoke(link, frame);
    }

    @SuppressWarnings("unchecked")
    private List<Protocol.Frame> drainWrites(CameraLink link) throws Exception {
        Field field = CameraLink.class.getDeclaredField("writes");
        field.setAccessible(true);
        ArrayDeque<byte[]> writes = (ArrayDeque<byte[]>) field.get(link);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        while (!writes.isEmpty()) {
            byte[] fragment = writes.remove();
            stream.write(fragment, 0, fragment.length);
        }
        return new Protocol.Decoder().accept(stream.toByteArray());
    }

    @Test public void approvedModelsSubscribeAndSendTheirOwnModeId() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("camera", Context.MODE_PRIVATE).edit().clear().commit();
        for (CameraModel model : CameraModel.values()) {
            CameraLink link = link();
            try {
                approve(link, model.deviceId, 0);
                assertTrue(link.ready);
                assertSame(model, link.cameraModel);
                assertTrue(link.message.contains(model.displayName));
                assertEquals(model.experimental, link.message.contains("experimental"));
                // No pairing address is saved until a valid live status comes from the link.
                assertFalse(app.getSharedPreferences("camera", Context.MODE_PRIVATE).contains("paired_address"));
                List<Protocol.Frame> handshake = drainWrites(link);
                assertEquals(2, handshake.size());
                assertTrue(handshake.get(0).response());
                assertEquals(71, handshake.get(0).seq);
                assertEquals(0x19, handshake.get(0).id);
                assertEquals(5, handshake.get(1).id);
                assertEquals(0x1d, handshake.get(1).set);
                assertArrayEquals(new byte[]{3, 20, 0, 0, 0, 0}, handshake.get(1).payload);

                link.mode = 1; link.state = 1; link.statusAt = SystemClock.elapsedRealtime();
                link.switchMode(5);
                List<Protocol.Frame> commands = drainWrites(link);
                assertEquals(1, commands.size());
                Protocol.Frame command = commands.get(0);
                assertEquals(0x1d, command.set);
                assertEquals(4, command.id);
                assertEquals(model.deviceId, Protocol.u16(command.payload, 2));
                assertEquals(5, command.payload[4]);
                assertTrue(link.busy);
            } finally { link.close(); }
            assertNull(link.cameraModel);
        }
    }

    @Test public void unsupportedModelsNeverSubscribeOrSendControls() throws Exception {
        // Osmo360 uses different modes; unknown IDs and lookalike low words aren't supported.
        for (long id : new long[]{0xff66, 0xff22, 0xffff, 0x0100ff33L, 0xff330000L}) {
            CameraLink link = link();
            approve(link, id, 0);
            assertFalse(link.ready);
            assertNull(link.cameraModel);
            assertTrue(link.message.contains("Unsupported camera ID"));
            link.switchMode(5); link.record(); link.takePhoto(); link.shutter();
            assertTrue(drainWrites(link).isEmpty());
        }
    }

    @Test public void decliningPairingRejectsEveryRecognizedModel() throws Exception {
        for (CameraModel model : CameraModel.values()) {
            CameraLink link = link();
            approve(link, model.deviceId, 1);
            assertFalse(link.ready);
            assertNull(link.cameraModel);
            assertTrue(link.message.contains("declined"));
            assertTrue(drainWrites(link).isEmpty());
        }
    }
}

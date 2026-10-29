package dev.crow.karoo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Only reachable through immutable, explicit pending intents created by Crow's ride field. */
public final class CameraActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        CameraSession session = CameraSession.get(context);
        if (!session.inUse() || !session.permissionsGranted()) return;
        perform(session, intent.getAction());
    }
    static void perform(CameraSession session, String action) {
        if ("shutter".equals(action)) session.link.shutter();
        else if ("record".equals(action)) session.link.record();
        else if ("take-photo".equals(action)) session.link.takePhoto();
        else if ("toggle-mode".equals(action)) session.link.switchMode(session.link.mode == 5 ? 1 : 5);
        else if ("photo".equals(action)) session.link.switchMode(5);
        else if ("video".equals(action)) session.link.switchMode(1);
        else if ("reconnect".equals(action)) session.reconnectIfAllowed();
    }
}

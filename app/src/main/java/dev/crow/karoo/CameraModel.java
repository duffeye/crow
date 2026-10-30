package dev.crow.karoo;

/** Models with DJI-documented R SDK IDs and the conventional Video/Photo modes. */
enum CameraModel {
    ACTION_4(0xff33, "Osmo Action 4", false),
    ACTION_5_PRO(0xff44, "Osmo Action 5 Pro", true),
    ACTION_6(0xff55, "Osmo Action 6", true);

    final int deviceId;
    final String displayName;
    final boolean experimental;

    CameraModel(int deviceId, String displayName, boolean experimental) {
        this.deviceId = deviceId;
        this.displayName = displayName;
        this.experimental = experimental;
    }

    static CameraModel fromDeviceId(long deviceId) {
        for (CameraModel model : values()) if (model.deviceId == deviceId) return model;
        return null;
    }
}

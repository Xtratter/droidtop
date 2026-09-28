# R8 rules for DroidTop.
# Shell.shizukuSh() calls the private Shizuku.newProcess() through reflection,
# so R8 must neither remove nor rename it.
-keep class rikka.shizuku.Shizuku {
    private static *** newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}

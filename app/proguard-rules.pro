# SafeOpen uses no reflection-based serialization; defaults are enough.
# Strip debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
-repackageclasses ''
-allowaccessmodification

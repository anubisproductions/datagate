# The app has almost no dependencies and uses no reflection, so R8's defaults are enough.
# Keep the manifest-declared components explicitly - R8 finds them via the manifest, but
# being explicit costs nothing and makes a stripped-out component obvious if it happens.
-keep class com.anubisproductions.datagate.MainActivity { *; }
-keep class com.anubisproductions.datagate.BlockingActivity { *; }
-keep class com.anubisproductions.datagate.BlockVpnService { *; }
-keep class com.anubisproductions.datagate.BootReceiver { *; }

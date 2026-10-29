# Keep Xposed module entry
-keep class com.applockisland.xposed.AppLockModule { *; }

# Keep all hook classes
-keep class com.applockisland.xposed.hook.** { *; }

# Keep island dispatch classes
-keep class com.applockisland.xposed.islanddispatch.** { *; }

# Keep ConfigManager
-keep class com.applockisland.xposed.ConfigManager { *; }

# Keep resources
-keep class com.applockisland.R$* { *; }

# The Termux terminal emulator uses JNI and reflective terminal plumbing.
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }

package app.fifteen

// App-private storage is already readable only by Fifteen, and it's left out of Android backups.
actual fun sealSecret(plain: String): String = plain

actual fun openSecret(stored: String): String = stored

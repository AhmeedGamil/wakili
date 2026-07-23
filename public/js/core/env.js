// Environment detection, shared so every module agrees on the answer.

// Running inside the native app shell (its WebView tags the user agent).
// Old installs still say ZogagApp — keep matching both.
export const IS_NATIVE = /WakiliApp|ZogagApp/i.test(navigator.userAgent);

package jamorham.keks.util;

/**
 * JamOrHam — logging bridge for AAPS (was System.out in upstream libkeks).
 */
public class Log {

    public static void d(final String TAG, final String msg) {
        android.util.Log.d(TAG, msg);
    }

    public static void l(final String msg) {
        d("KEKS-Plugin", msg);
    }
}

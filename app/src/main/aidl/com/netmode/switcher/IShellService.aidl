package com.netmode.switcher;

/** Runs inside the Shizuku-provided process, which holds shell (or root) privileges. */
interface IShellService {
    String exec(String cmd) = 1;
    // Shizuku reserves this transaction id for tearing a user service down; AIDL then requires
    // an explicit id on every other method too.
    void destroy() = 16777114;
}

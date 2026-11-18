package dev.techlab.android.binder;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface ITransferSource {
    Bundle describe(String id);
    byte[] readChunk(String id, int offset, int length);
    ParcelFileDescriptor openFile(String id);
    ParcelFileDescriptor openPipe(String id, boolean interruptWriter);
    ParcelFileDescriptor encodeQuestion(boolean stream);
    void fillRegion(String id, in Bundle region);
    Bundle diagnostics();
}

package vn.codex.skinlab;

import android.os.ParcelFileDescriptor;

interface IMapFile {
    ParcelFileDescriptor openOriginal() = 0;
    boolean canWrite() = 1;
    void replace(in ParcelFileDescriptor replacement) = 2;
    String probe() = 3;
    void fixData(in ParcelFileDescriptor archive) = 4;
    void installResources(in ParcelFileDescriptor archive, boolean replace) = 5;
    int deleteResourceBackups() = 6;
    boolean canWriteResources() = 7;
    void destroy() = 16777114;
}

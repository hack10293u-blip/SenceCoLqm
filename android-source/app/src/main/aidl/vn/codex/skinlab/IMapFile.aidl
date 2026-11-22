package vn.codex.skinlab;

import android.os.ParcelFileDescriptor;

interface IMapFile {
    ParcelFileDescriptor openOriginal() = 0;
    boolean canWrite() = 1;
    void replace(in ParcelFileDescriptor replacement) = 2;
    String probe() = 3;
    void fixData(in ParcelFileDescriptor archive) = 4;
    void removeMap() = 5;
    boolean mapExists() = 6;
    void installArchive(in ParcelFileDescriptor archive, boolean replace) = 7;
    void writeResource(String relative, in ParcelFileDescriptor replacement) = 8;
    ParcelFileDescriptor readResource(String relative) = 9;
    boolean resourceExists(String relative) = 10;
    void destroy() = 16777114;
}

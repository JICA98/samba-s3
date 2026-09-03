package com.zenithblue.sambas3.ppu;

import com.zenithblue.sambas3.ppu.IPpuBatchCallback;

interface IPpuBatchWorker {
    void startBatch(
        long logicalSessionId,
        long logicalJobId,
        String titleId,
        String gamePath,
        String userId,
        int batchIndex,
        int maxNewObjects,
        String manifestKey,
        IPpuBatchCallback callback
    );

    void cancel(long logicalSessionId);

    int getWorkerPid();

    String getWorkerInstanceId();
}

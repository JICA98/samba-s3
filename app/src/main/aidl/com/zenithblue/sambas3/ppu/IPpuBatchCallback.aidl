package com.zenithblue.sambas3.ppu;

interface IPpuBatchCallback {
    void onBatchStarted(
        long logicalSessionId,
        int workerPid,
        String workerInstanceId,
        int batchIndex
    );

    void onProgress(
        long logicalSessionId,
        long logicalJobId,
        int totalModules,
        int completedModules,
        String message
    );

    void onBatchFinished(
        long logicalSessionId,
        long logicalJobId,
        int batchIndex,
        String resultJson
    );
}

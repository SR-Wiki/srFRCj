package com.HIT.WeisongZhao.utils;

/** Select all stack frames or the currently displayed frame/pair. */
public final class StackFrames {
    private StackFrames() {}

    /** Index of the current frame/pair in the computed results, or -1 if unavailable. */
    public static int previewIndex(int frames, int currentFrame, boolean singleFrame, boolean batchProcessing) {
        int available = singleFrame ? frames : frames - 1;
        if (currentFrame < 1 || currentFrame > available) return -1;
        return batchProcessing ? currentFrame - 1 : 0;
    }

    public static int[] select(int frames, int currentFrame, boolean singleFrame, boolean batchProcessing) {
        int available = singleFrame ? frames : frames - 1;
        if (available < 1)
            throw new IllegalArgumentException(singleFrame
                    ? "The input stack is empty. Open an image or stack to continue."
                    : "Paired mode needs at least two frames in the input stack.");
        if (!batchProcessing) {
            if (currentFrame < 1 || currentFrame > available)
                throw new IllegalArgumentException(singleFrame
                        ? "Select a frame within the input stack, then run the analysis again."
                        : "Select a frame before the last frame. Paired mode uses the selected frame and the following frame.");
            return new int[] {currentFrame};
        }
        int[] selected = new int[available];
        for (int i = 0; i < available; i++) selected[i] = i + 1;
        return selected;
    }

    public static String label(int frame, boolean singleFrame) {
        return singleFrame ? "Frame " + frame : "Frames " + frame + "-" + (frame + 1);
    }
}

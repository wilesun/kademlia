package com.offbynull.voip.audio.internalmessages;

import java.util.Arrays;
import org.apache.commons.lang3.Validate;

public class OutputPCMBlock {
    private final byte[] data;

    public OutputPCMBlock(byte[] data) {
        Validate.notNull(data);
        this.data = Arrays.copyOf(data, data.length);
    }

    public byte[] getData() {
        return Arrays.copyOf(data, data.length);
    }
}

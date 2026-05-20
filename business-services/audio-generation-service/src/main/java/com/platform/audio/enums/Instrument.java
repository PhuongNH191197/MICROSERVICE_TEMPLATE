package com.platform.audio.enums;
public enum Instrument {
    PIANO("Piano"), GUITAR("Guitar"), VIOLIN("Violin"),
    SAXOPHONE("Saxophone"), DRUMS("Drums"), FLUTE("Flute");
    private final String label;
    Instrument(String label) { this.label = label; }
    public String getLabel() { return label; }
}

package com.platform.audio.enums;
public enum Mood {
    HAPPY("Happy"), RELAX("Relax"), ENERGIZE("Energize"),
    ROMANTIC("Romantic"), SAD("Sad"), MOTIVATION("Motivation");
    private final String label;
    Mood(String label) { this.label = label; }
    public String getLabel() { return label; }
}

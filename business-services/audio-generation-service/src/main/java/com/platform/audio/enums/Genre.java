package com.platform.audio.enums;
public enum Genre {
    POP("Pop"), ROCK("Rock"), CLASSICAL("Classical"),
    ELECTRONIC("Electronic"), ACOUSTIC("Acoustic"), JAZZ("Jazz");
    private final String label;
    Genre(String label) { this.label = label; }
    public String getLabel() { return label; }
}

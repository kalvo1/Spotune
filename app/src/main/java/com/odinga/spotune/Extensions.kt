package com.odinga.spotune

import android.media.MediaPlayer

var MediaPlayer.volume: Float
    get() = 1f
    set(value) {
        this.setVolume(value, value)
    }
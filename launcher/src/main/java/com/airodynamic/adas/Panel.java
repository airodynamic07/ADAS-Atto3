package com.airodynamic.adas;

import android.view.View;

/** Something a window can show. */
public interface Panel {
    View view();
    void onCar(CarData.State s);
    /** Visible on screen (start camera etc.) or not. */
    void setActive(boolean active);
}

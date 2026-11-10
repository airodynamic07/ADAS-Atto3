package com.airodynamic.adas;

import android.view.View;

/**
 * Middle window: Engine Sound (with Ghost Race inside it) running right here in the launcher.
 * The page, its sounds and the car connection live in {@link EngineHost}, which the launcher
 * keeps alive, so changing theme (incl. Auto Sport) or opening another app never stops the sound.
 */
public final class EnginePanel implements Panel {
    private final EngineHost host;

    public EnginePanel(EngineHost host, Theme th) {
        this.host = host;
        host.setTheme(th);
    }

    @Override public View view() { return host.view(); }
    @Override public void onCar(CarData.State s) {}
    @Override public void setActive(boolean a) {}
}

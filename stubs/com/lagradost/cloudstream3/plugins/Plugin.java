package com.lagradost.cloudstream3.plugins;
import android.content.Context;
import kotlin.jvm.functions.Function1;
public abstract class Plugin extends BasePlugin {
    public Function1<Context, kotlin.Unit> openSettings;
    public void load(Context context) throws Throwable { load(); }
}

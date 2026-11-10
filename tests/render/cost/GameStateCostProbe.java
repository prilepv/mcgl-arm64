package local.mcgl.render.tests;

import java.lang.management.ManagementFactory;
import java.util.Locale;
import local.mcgl.render.*;
import org.lwjgl.opengl.OffscreenCoreContext;

/** Native context, but no geometry/account/world. Isolates Java command preparation. */
public final class GameStateCostProbe {
    private static final int COUNT=100000;
    public static void main(String[] args)throws Exception {
        OffscreenCoreContext.open();
        try {
            GameRenderCommands g=RenderSystem.game();
            com.sun.management.ThreadMXBean cpu=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
            long thread=Thread.currentThread().getId();
            for(int round=0;round<5;round++){
                long before=cpu.getThreadAllocatedBytes(thread),start=cpu.getCurrentThreadCpuTime();
                for(int i=0;i<COUNT;i++){
                    g.glPushMatrix();g.glTranslated(.25,-.5,.75);g.glScaled(1.5,.5,2);g.glPopMatrix();
                    g.glColor4f(.2f,.4f,.6f,1);g.glNormal3f(0,0,1);g.glMultiTexCoord2f(33984,.25f,.75f);
                    g.glEnable(2896);g.glDisable(2896);g.glCallList(9000000);
                }
                long nanos=cpu.getCurrentThreadCpuTime()-start,allocated=cpu.getThreadAllocatedBytes(thread)-before;
                System.out.printf(Locale.ROOT,"GAME_STATE_COST round=%d bytes_per_group=%.1f cpu_ns_per_group=%.1f synthetic-not-world-fps%n",round,allocated/(double)COUNT,nanos/(double)COUNT);
            }
        }finally{OffscreenCoreContext.close();}
    }
}

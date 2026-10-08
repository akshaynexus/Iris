package harness;
import java.util.*;
import org.lwjgl.opengl.GL33C;
/** Nonblocking GL timestamp pairs, or mcopt's retired command-buffer timestamps. */
public final class GpuProbe {
 private static final boolean ON=Boolean.getBoolean("harness.gpuTimes");
 private static final boolean METAL=Boolean.getBoolean("mcopt.metal");
 private static final int[] queries=new int[32];
 private static final boolean[] pending=new boolean[16];
 private static final ArrayDeque<Double> times=new ArrayDeque<>();
 private static int slot;
 public static void begin(){
  if(!ON||METAL)return;
  if(queries[0]==0)for(int i=0;i<32;i++)queries[i]=GL33C.glGenQueries();
  if(pending[slot]){
   if(GL33C.glGetQueryObjecti(queries[slot*2+1],GL33C.GL_QUERY_RESULT_AVAILABLE)==0)return;
   long start=GL33C.glGetQueryObjectui64(queries[slot*2],GL33C.GL_QUERY_RESULT);
   long end=GL33C.glGetQueryObjectui64(queries[slot*2+1],GL33C.GL_QUERY_RESULT);
   times.add((end-start)/1e6);if(times.size()>600)times.remove();pending[slot]=false;
  }
  GL33C.glQueryCounter(queries[slot*2],GL33C.GL_TIMESTAMP);
 }
 public static void end(){
  if(!ON||METAL||queries[0]==0||pending[slot])return;
  GL33C.glQueryCounter(queries[slot*2+1],GL33C.GL_TIMESTAMP);pending[slot]=true;slot=(slot+1)%16;
 }
 public static Object stats(int count)throws Exception{
  if(!ON)return Map.of("ok",false,"error","GPU profiling disabled");
  double[] samples;
  double completionMs=0;
  if(METAL){
   Map<?,?> data=(Map<?,?>)Class.forName("mcopt.metal.GpuTimes").getMethod("snapshot",Map.class).invoke(null,new LinkedHashMap<>());
   long[] starts=(long[])data.get("gpuStartNs"),ends=(long[])data.get("gpuEndNs");
   boolean[] presenting=(boolean[])data.get("presenting");
   var values=new ArrayList<Double>();long newest=0,oldest=0;
   for(int i=ends.length-1;i>=0&&values.size()<count;i--)if(starts[i]>0&&ends[i]>=starts[i]){values.add((ends[i]-starts[i])/1e6);if(newest==0)newest=ends[i];oldest=ends[i];}
   completionMs=values.size()>1?(newest-oldest)/1e6/(values.size()-1):0;
   samples=values.stream().mapToDouble(Double::doubleValue).toArray();
  }else samples=times.stream().skip(Math.max(0,times.size()-count)).mapToDouble(Double::doubleValue).toArray();
  Arrays.sort(samples);
  if(samples.length==0||Arrays.stream(samples).max().orElse(0)<=0)return Map.of("ok",true,"available",false,"reason","GPU timestamp query returned no positive durations");
  return Map.of("completion_interval_ms",completionMs,"available",true,"ok",samples.length>0,"samples",samples.length,"avg_ms",Arrays.stream(samples).average().orElse(0),"p95_ms",samples.length==0?0:samples[(int)((samples.length-1)*.95)],"signal",METAL?"command-buffer GPU latency (overlapping frames), all submits":"GL frame timestamp interval");
 }
}

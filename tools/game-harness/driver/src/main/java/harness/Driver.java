package harness;

import com.google.gson.Gson;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Commands are serialized, dispatched on the game thread, and acknowledged after completion. */
public class Driver implements ClientModInitializer {
 private static final Gson JSON = new Gson();
 private static final ArrayDeque<Double> TIMES = new ArrayDeque<>();
 private static long frames, last;
 private static boolean opened;
 private record Pose(double x, double y, double z, float yaw, float pitch) {}
 private static volatile Pose lockedPose;
 public static boolean poseLocked() { return lockedPose != null; }
 public static void lockPose() {
  Pose p=lockedPose;
  var player=Minecraft.getInstance().player;
  if(p==null || player==null) return;
  player.setPos(p.x,p.y,p.z); player.setYRot(p.yaw); player.setXRot(p.pitch);
  player.setDeltaMovement(0,0,0); player.setOldPosAndRot();
 }

 private static final Path OUT = Path.of(System.getProperty("harness.output", "harness-output"));
 public void onInitializeClient() {
  if (!Boolean.getBoolean("harness.enabled")) return;
  Thread.ofPlatform().daemon().name("harness-control").start(() -> {
   try (ServerSocket server = new ServerSocket(Integer.getInteger("harness.port",47821), 8, InetAddress.getByName("127.0.0.1"))) {
    while (true) try (Socket socket = server.accept()) {
     socket.setSoTimeout(130000);
     var reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
     var writer = new PrintWriter(socket.getOutputStream(), true);
     String line;
     while ((line = reader.readLine()) != null) {
      try { writer.println(JSON.toJson(command(line).get(125, TimeUnit.SECONDS))); }
      catch (Exception e) { writer.println(JSON.toJson(Map.of("ok",false,"error",e.toString()))); }
     }
    } catch (Exception e) { e.printStackTrace(); }
   } catch (Exception e) { throw new RuntimeException(e); }
  });
 }
 public static synchronized void frame() {
  if (!Boolean.getBoolean("harness.enabled")) return;
  long now=System.nanoTime();
  if(last!=0) { TIMES.add((now-last)/1e6); if(TIMES.size()>600) TIMES.remove(); }
  last=now; frames++; Driver.class.notifyAll();
  var mc=Minecraft.getInstance();
  mc.getFramerateLimitTracker().onInputReceived();
  if(!opened && mc.gui.screen() instanceof TitleScreen && mc.isGameLoadFinished()) {
   opened=true; mc.options.pauseOnLostFocus=false;
   mc.options.renderDistance().set(8); mc.options.simulationDistance().set(5);
   mc.options.fov().set(70); mc.options.guiScale().set(2);
   mc.options.enableVsync().set(false); mc.options.framerateLimit().set(260);
   mc.options.fullscreen().set(false); mc.getWindow().setWindowed(640,360);
   mc.options.save();
   mc.createWorldOpenFlows().openWorld("Harness", () -> {});
  }
  if(frames%30==0) try { Files.createDirectories(OUT); Files.writeString(OUT.resolve("heartbeat.json"),JSON.toJson(Map.of("frames",frames,"world",mc.level!=null))); } catch(IOException e) { throw new RuntimeException(e); }
 }
 private static CompletableFuture<Object> command(String line) {
  String[] a=line.trim().split("\\s+");
  if(a[0].equals("wait")) return CompletableFuture.supplyAsync(() -> {
   if(a.length!=3 || !a[1].equals("frames")) throw new IllegalArgumentException("wait frames N");
   int n=Integer.parseInt(a[2]); if(n<0 || n>10000) throw new IllegalArgumentException("frame count");
   synchronized(Driver.class) { long end=frames+n; long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
    while(frames<end) { if(System.nanoTime()>deadline) throw new RuntimeException("no frame completion within 120s"); try { Driver.class.wait(1000); } catch(InterruptedException e) { throw new RuntimeException(e); } }
   }
   return Map.of("ok",true,"frames",frames);
  });
  var result=new CompletableFuture<Object>();
  Minecraft.getInstance().execute(() -> {
   try {
    var mc=Minecraft.getInstance();
    switch(a[0]) {
     case "stats" -> { synchronized(Driver.class) { double[] t=TIMES.stream().skip(Math.max(0, TIMES.size() - (a.length > 1 ? Integer.parseInt(a[1]) : 600))).mapToDouble(Double::doubleValue).sorted().toArray(); double avg=Arrays.stream(t).average().orElse(0); result.complete(Map.of("ok",true,"frames",frames,"world",mc.level!=null,"samples",t.length,"throttle",mc.getFramerateLimitTracker().getThrottleReason().name(),"avg_ms",avg,"p95_ms",t.length==0?0:t[(int)((t.length-1)*.95)],"fps",avg==0?0:1000/avg)); } }
     case "position" -> {
      if(mc.player==null) throw new IllegalStateException("world not ready");
      result.complete(Map.of("ok",true,"x",mc.player.getX(),"y",mc.player.getY(),"z",mc.player.getZ(),"yaw",mc.player.getYRot(),"pitch",mc.player.getXRot(),"width",mc.getWindow().getWidth(),"height",mc.getWindow().getHeight()));
     }
     case "resetstats" -> { synchronized(Driver.class) { TIMES.clear(); } result.complete(Map.of("ok",true)); }
     case "screenshot" -> {
      String name=safe(a[1]); Files.createDirectories(OUT.resolve("screenshots"));
      Screenshot.grab(OUT.toFile(), name+".png", mc.gameRenderer.mainRenderTarget(), 1, msg -> result.complete(Map.of("ok",Files.exists(OUT.resolve("screenshots/"+name+".png")),"path",OUT.resolve("screenshots/"+name+".png").toString(),"message",msg.getString())));
     }
     case "glstages" -> { result.complete(GlProbe.queue(OUT.resolve("dumps").resolve(safe(a[1])))); }
     case "gldump" -> { result.complete(GlProbe.dump(OUT.resolve("dumps").resolve(safe(a[1])))); }
     case "dumpstatus" -> { result.complete(Class.forName("net.irisshaders.iris.metal.MetalPackPipeline").getMethod("dumpStatus").invoke(null)); }
     case "dump" -> { var type=Class.forName("net.irisshaders.iris.metal.MetalPackPipeline"); result.complete(type.getMethod("requestDump",Path.class).invoke(null,OUT.resolve("dumps").resolve(safe(a[1])))); }
     case "hud" -> { if(mc.gui.hud.isHidden()!=a[1].equals("off")) mc.gui.hud.toggle(); result.complete(Map.of("ok",true)); }
     case "quit" -> { result.complete(Map.of("ok",true)); mc.stop(); }
     case "tp", "time", "weather", "setup" -> {
      var server=mc.getSingleplayerServer(); if(server==null || mc.player==null) throw new IllegalStateException("world not ready");
      var uuid=mc.player.getUUID();
      server.execute(() -> { try {
       var player=server.getPlayerList().getPlayer(uuid); var src=server.createCommandSourceStack();
       String cmd=switch(a[0]) {
        case "tp" -> { if(a.length!=6) throw new IllegalArgumentException("tp x y z yaw pitch"); for(int i=1;i<6;i++) if(!Double.isFinite(Double.parseDouble(a[i]))) throw new IllegalArgumentException("finite numbers required"); yield "tp "+player.getGameProfile().name()+" "+String.join(" ",Arrays.copyOfRange(a,1,6)); }
        case "time" -> "time set "+Integer.parseInt(a[1]);
        case "weather" -> { if(!Set.of("clear","rain").contains(a[1])) throw new IllegalArgumentException("weather"); yield "weather "+a[1]; }
        default -> "gamemode spectator "+player.getGameProfile().name();
       };
       server.getCommands().performPrefixedCommand(src,cmd);
       if(a[0].equals("tp")) lockedPose=new Pose(player.getX(),player.getY(),player.getZ(),player.getYRot(),player.getXRot());
       if(a[0].equals("setup")) for(String rule:List.of("advance_time","advance_weather","spawn_mobs")) server.getCommands().performPrefixedCommand(src,"gamerule minecraft:"+rule+" false");
       result.complete(Map.of("ok",true));
      } catch(Exception e) { result.completeExceptionally(e); } });
     }
     default -> throw new IllegalArgumentException("unknown command");
    }
   } catch(Exception e) { result.completeExceptionally(e); }
  }); return result;
 }
 private static String safe(String s) { if(!s.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("invalid name"); return s; }
}

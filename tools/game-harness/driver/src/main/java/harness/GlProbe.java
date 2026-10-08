package harness;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.Map;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

/** Optional reference diagnostic; all Iris access is reflective to keep the driver independent. */
public final class GlProbe {
 private static Path stages;
 public static Object queue(Path dir) { stages=dir; return Map.of("ok",true,"path",dir.toString()); }
 public static void stage(Object pipeline, String name) {
  if(stages==null)return;
  try {
   var field=pipeline.getClass().getDeclaredField("renderTargets");field.setAccessible(true);
   Object targets=field.get(pipeline); Path dir=stages.resolve(name);
   for(int i=0;i<(int)targets.getClass().getMethod("getRenderTargetCount").invoke(targets);i++) {
    Object t=targets.getClass().getMethod("get",int.class).invoke(targets,i);if(t==null)continue;
    int w=(int)t.getClass().getMethod("getWidth").invoke(t),h=(int)t.getClass().getMethod("getHeight").invoke(t);
    for(String side:new String[]{"Main","Alt"}) capture((int)t.getClass().getMethod("get"+side+"Texture").invoke(t),w,h,false,dir.resolve("colortex"+i+"-"+side.toLowerCase()+".png"));
   }
   String[] methods={"getDepthTexture","getDepthTextureNoTranslucents","getDepthTextureNoHand"};
   for(int i=0;i<3;i++){GlTexture t=(GlTexture)targets.getClass().getMethod(methods[i]).invoke(targets);capture(t.glId(),t.getWidth(0),t.getHeight(0),true,dir.resolve("depthtex"+i+".png"));}
  } catch(Exception e){e.printStackTrace();stages=null;}
  if(name.equals("after-composite"))stages=null;
 }
 private static void capture(int id,int w,int h,boolean depth,Path path)throws Exception {
  int old=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D),channels=depth?1:4;
  var data=MemoryUtil.memAllocFloat(w*h*channels);
  try {
   GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,id);
   GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D,0,depth?GL11C.GL_DEPTH_COMPONENT:GL11C.GL_RGBA,GL11C.GL_FLOAT,data);
   float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
   for(int i=0;i<w*h;i++)for(int c=0;c<(depth?1:3);c++){float v=data.get(i*channels+c);if(Float.isFinite(v)){min=Math.min(min,v);max=Math.max(max,v);}}
   BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
   for(int y=0;y<h;y++)for(int x=0;x<w;x++){int rgb=0;for(int c=0;c<3;c++){float v=data.get((y*w+x)*channels+(depth?0:c));if(depth||min<0||max>1)v=(v-min)/Math.max(1e-20f,max-min);rgb=(rgb<<8)|Math.clamp(Math.round(v*255),0,255);}image.setRGB(x,y,rgb);}
   Files.createDirectories(path.getParent());ImageIO.write(image,"PNG",path.toFile());Files.writeString(Path.of(path+".txt"),"min="+min+" max="+max);
  } finally {GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,old);MemoryUtil.memFree(data);}
 }
 static Object dump(Path dir) throws Exception {
  Object manager=Class.forName("net.irisshaders.iris.Iris").getMethod("getPipelineManager").invoke(null);
  Object pipeline=manager.getClass().getMethod("getPipelineNullable").invoke(manager);
  var field=pipeline.getClass().getDeclaredField("shadowRenderTargets");field.setAccessible(true);
  Object targets=field.get(pipeline);
  GlTexture texture=(GlTexture)targets.getClass().getMethod("getDepthTexture").invoke(targets);
  int old=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
  int w=texture.getWidth(0),h=texture.getHeight(0);
  var data=MemoryUtil.memAllocFloat(w*h);
  try {
   GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,texture.glId());
   GL11C.glGetTexImage(GL11C.GL_TEXTURE_2D,0,GL11C.GL_DEPTH_COMPONENT,GL11C.GL_FLOAT,data);
   float min=1,max=0;for(int i=0;i<w*h;i++){min=Math.min(min,data.get(i));max=Math.max(max,data.get(i));}
   BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
   for(int y=0;y<h;y++)for(int x=0;x<w;x++){int v=Math.round(data.get(y*w+x)*255);image.setRGB(x,y,(v<<16)|(v<<8)|v);}
   Files.createDirectories(dir);ImageIO.write(image,"PNG",dir.resolve("shadowtex0.png").toFile());
   Files.writeString(dir.resolve("shadowtex0.txt"),"min="+min+" max="+max+" clear_pixel="+data.get(0));
   return Map.of("ok",true,"path",dir.toString(),"min",min,"max",max);
  } finally {GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,old);MemoryUtil.memFree(data);}
 }
}

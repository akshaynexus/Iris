package net.irisshaders.iris.metal;

import mcopt.metal.MetalBridge;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.*;

/** Diagnostic only: copies after GPU completion and encodes PNGs off the render thread. */
final class MetalDump {
 static final java.util.concurrent.atomic.AtomicInteger PENDING = new java.util.concurrent.atomic.AtomicInteger();
 static volatile String error;
 private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
  Thread t = new Thread(r, "iris-buffer-dumps"); t.setDaemon(true); return t;
 });
 static void texture(Object encoder, long texture, int format, int width, int height, Path file) {
  if(texture==0) return;
  int channels=switch(format) { case 92,93 -> 3; case 10,12,13,14,20,22,23,24,25,53,54,55,252 -> 1; case 30,32,33,34,60,62,63,64,65,103,104,105 -> 2; default -> 4; };
  int bits=switch(format) { case 20,22,23,24,25,60,62,63,64,65,110,112,113,114,115 -> 16; case 53,54,55,103,104,105,123,124,125,252 -> 32; default -> 8; };
  boolean floating= format==25 || format==65 || format==115 || format==55 || format==105 || format==125 || format==252;
  boolean signed= switch(format) { case 12,14,22,24,32,34,54,62,64,72,74,104,112,114,124 -> true; default -> false; };
  boolean packedFormat = format >= 90 && format <= 93;
  PENDING.incrementAndGet();
  MetalBridge.readTextureAsync(encoder,texture,width,height,packedFormat ? 4 : channels*bits/8, bytes -> WRITER.execute(() -> {
   try {
    ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    float[] values=new float[width*height*channels];
    float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
    int invalid=0, packed=0;
    for(int i=0;i<values.length;i++) {
     float v;
     if(packedFormat) { if(i%channels==0) packed=b.getInt(); v=unpack(packed,i%channels,format); }
     else if(floating) v=bits==16?Float.float16ToFloat(b.getShort()):b.getFloat();
     else if(bits==8) { byte n=b.get(); v=signed?n/127f:Byte.toUnsignedInt(n)/255f; }
     else if(bits==16) { short n=b.getShort(); v=signed?n/32767f:Short.toUnsignedInt(n)/65535f; }
     else { int n=b.getInt(); v=signed?n/(float)Integer.MAX_VALUE:Integer.toUnsignedLong(n)/(float)0xffffffffL; }
     values[i]=v;
     if(Float.isFinite(v)) { min=Math.min(min,v); max=Math.max(max,v); } else invalid++;
    }
    BufferedImage img=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
    // Depth and out-of-range diagnostic channels use global min/max; LDR is preserved.
    boolean normalize=format==252 || min<0 || max>1;
    for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
     int rgb=0;
     for(int c=0;c<3;c++) {
      float v=values[(y*width+x)*channels+(channels==1?0:Math.min(c,channels-1))];
      if(normalize && max>min) v=(v-min)/(max-min);
      if(!Float.isFinite(v)) v=0;
      rgb=(rgb<<8)|Math.round(Math.clamp(v,0f,1f)*255);
     }
     img.setRGB(x,y,rgb);
    }
    Files.createDirectories(file.getParent()); ImageIO.write(img,"PNG",file.toFile());
    note(file,"format="+format+" channels="+channels+" bits="+bits+" min="+min+" max="+max+" nonfinite="+invalid+" normalization="+(normalize?"minmax":"identity")+" orientation=native");
   } catch(Exception e) { error=e.toString(); note(file,"ERROR "+e); } finally { PENDING.decrementAndGet(); }
  }));
 }
 private static float unpack(int word, int channel, int format) {
  if(format==90 || format==91) return channel==3 ? (word>>>30)/3f : ((word>>>(channel*10))&1023)/1023f;
  if(format==93) return Math.scalb((float)((word>>>(channel*9))&511), (word>>>27)-24);
  int mantissaBits=channel==2?5:6;
  int value=(word>>>(channel*11)) & ((1<<(mantissaBits+5))-1);
  int exponent=value>>>mantissaBits, mantissa=value&((1<<mantissaBits)-1);
  if(exponent==31) return mantissa==0?Float.POSITIVE_INFINITY:Float.NaN;
  return exponent==0 ? Math.scalb((float)mantissa,1-15-mantissaBits)
   : Math.scalb(1f+mantissa/(float)(1<<mantissaBits),exponent-15);
 }
 private static void note(Path file,String text) { try { Files.createDirectories(file.getParent()); Files.writeString(Path.of(file+".txt"),text+"\n"); } catch(Exception e) { e.printStackTrace(); } }
}

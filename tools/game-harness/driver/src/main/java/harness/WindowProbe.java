package harness;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.*;
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDLProperties;
import static java.lang.foreign.ValueLayout.*;
/** Harness-only App Nap prevention and actual Cocoa occlusion diagnostics. */
public final class WindowProbe {
 private static long activity;
 private static String error="";
 private static final SymbolLookup LIB=SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib",Arena.global());
 private static MethodHandle fn(String name,FunctionDescriptor desc){return Linker.nativeLinker().downcallHandle(LIB.find(name).orElseThrow(),desc);}
 private static final MethodHandle SEL=fn("sel_registerName",FunctionDescriptor.of(JAVA_LONG,ADDRESS));
 private static final MethodHandle CLS=fn("objc_getClass",FunctionDescriptor.of(JAVA_LONG,ADDRESS));
 private static final MethodHandle MSG=fn("objc_msgSend",FunctionDescriptor.of(JAVA_LONG,JAVA_LONG,JAVA_LONG));
 private static long sel(String s)throws Throwable{try(var a=Arena.ofConfined()){return(long)SEL.invokeExact(a.allocateFrom(s));}}
 private static long cls(String s)throws Throwable{try(var a=Arena.ofConfined()){return(long)CLS.invokeExact(a.allocateFrom(s));}}
 public static void preventNap(){try(var a=Arena.ofConfined()){
  long process=(long)MSG.invokeExact(cls("NSProcessInfo"),sel("processInfo"));
  var str=fn("objc_msgSend",FunctionDescriptor.of(JAVA_LONG,JAVA_LONG,JAVA_LONG,ADDRESS));
  long reason=(long)str.invokeExact(cls("NSString"),sel("stringWithUTF8String:"),a.allocateFrom("Minecraft reproducible rendering benchmark"));
  var begin=fn("objc_msgSend",FunctionDescriptor.of(JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_LONG));
  activity=(long)begin.invokeExact(process,sel("beginActivityWithOptions:reason:"),0x00FFFFFFL,reason);
 }catch(Throwable t){error=t.toString();}}
 public static void background(){
  if(!Boolean.getBoolean("harness.background"))return;
  long w=Minecraft.getInstance().getWindow().handle();
  SDLVideo.SDL_SetWindowAlwaysOnTop(w,true);
  SDLVideo.SDL_SetWindowPosition(w,-Integer.getInteger("harness.width",2880)/2+96,32);
  try {
   long cocoa=SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(w),SDLVideo.SDL_PROP_WINDOW_COCOA_WINDOW_POINTER,0);
   var set=fn("objc_msgSend",FunctionDescriptor.ofVoid(JAVA_LONG,JAVA_LONG,JAVA_LONG));
   set.invokeExact(cocoa,sel("setHidesOnDeactivate:"),0L);
   set.invokeExact(cocoa,sel("setCollectionBehavior:"),1L); // all Spaces, no fullscreen Space
   set.invokeExact(cocoa,sel("setIgnoresMouseEvents:"),1L);
  }catch(Throwable t){error=t.toString();}
 }
 public static Map<String,Object> state(){
  long w=Minecraft.getInstance().getWindow().handle();var out=new LinkedHashMap<String,Object>();
  out.put("focused",(SDLVideo.SDL_GetWindowFlags(w)&SDLVideo.SDL_WINDOW_INPUT_FOCUS)!=0);
  out.put("iconified",(SDLVideo.SDL_GetWindowFlags(w)&SDLVideo.SDL_WINDOW_MINIMIZED)!=0);
  out.put("visible",(SDLVideo.SDL_GetWindowFlags(w)&SDLVideo.SDL_WINDOW_HIDDEN)==0);
  out.put("app_nap_prevented",activity!=0);
  try{long flags=(long)MSG.invokeExact(SDLProperties.SDL_GetPointerProperty(SDLVideo.SDL_GetWindowProperties(w),SDLVideo.SDL_PROP_WINDOW_COCOA_WINDOW_POINTER,0),sel("occlusionState"));out.put("cocoa_visible",(flags&2)!=0);}catch(Throwable t){error=t.toString();}
  try(var stack=org.lwjgl.system.MemoryStack.stackPush()){
   var x=stack.mallocInt(1);var y=stack.mallocInt(1);SDLVideo.SDL_GetWindowPosition(w,x,y);out.put("x",x.get(0));out.put("y",y.get(0));
  }
  out.put("error",error);return out;
 }
}

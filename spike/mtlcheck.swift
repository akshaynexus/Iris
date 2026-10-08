// Compiles every .metal file given on the command line with MTLDevice.makeLibrary(source:), as mcopt does at run time.
import Metal
import Foundation
let device = MTLCreateSystemDefaultDevice()!
let opts = MTLCompileOptions()
opts.languageVersion = .version3_0
var ok = 0, fail = 0
for path in CommandLine.arguments.dropFirst() {
    let src = try! String(contentsOfFile: path, encoding: .utf8)
    do { _ = try device.makeLibrary(source: src, options: opts); ok += 1 }
    catch { fail += 1; print("== \((path as NSString).lastPathComponent): metal\n\(error)\n") }
}
print("metal_ok=\(ok) metal_fail=\(fail)")

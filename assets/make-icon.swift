// Usage: swift assets/make-icon.swift assets/icon-source.jpeg out.png    (then iconutil, see bundle.sh)
//        swift assets/make-icon.swift assets/icon-source.jpeg assets/watermark.rgba
// .png:  1024px macOS icon, 824px rounded tile on Apple's icon grid.
// .rgba: 128px rounded tile, no margin, raw premultiplied RGBA for the in-app watermark.
import AppKit
let a = CommandLine.arguments
let img = NSImage(contentsOfFile: a[1])!
let raw = a[2].hasSuffix(".rgba")
let size = raw ? 128 : 1024
let tile = raw ? Double(size) : 824.0, inset = (Double(size) - tile) / 2
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size, bitsPerSample: 8,
                           samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                           bytesPerRow: size * 4, bitsPerPixel: 32)!
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSGraphicsContext.current!.imageInterpolation = .high
let rect = NSRect(x: inset, y: inset, width: tile, height: tile)
NSBezierPath(roundedRect: rect, xRadius: tile * 0.225, yRadius: tile * 0.225).addClip()
img.draw(in: rect)
NSGraphicsContext.current = nil
let out = URL(fileURLWithPath: a[2])
if raw {
    try! Data(bytes: rep.bitmapData!, count: size * size * 4).write(to: out)
} else {
    try! rep.representation(using: .png, properties: [:])!.write(to: out)
}

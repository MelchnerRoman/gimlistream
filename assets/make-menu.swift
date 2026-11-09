// Usage: swift assets/make-menu.swift assets/icon-source.jpeg assets/menu.rgb
// Renders the desktop start screen: 1280x720 raw RGB. Button rects must match MENU_BUTTONS in src/main.rs.
import AppKit
let a = CommandLine.arguments
let (W, H) = (1280, 720)
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: W, pixelsHigh: H, bitsPerSample: 8,
                           samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                           bytesPerRow: W * 4, bitsPerPixel: 32)!
let cg = NSGraphicsContext(bitmapImageRep: rep)!.cgContext
cg.translateBy(x: 0, y: CGFloat(H)); cg.scaleBy(x: 1, y: -1) // top-left origin like the Rust side
NSGraphicsContext.current = NSGraphicsContext(cgContext: cg, flipped: true)
NSGraphicsContext.current!.imageInterpolation = .high
NSColor.black.setFill()
NSRect(x: 0, y: 0, width: W, height: H).fill()

func text(_ s: String, _ size: CGFloat, _ weight: NSFont.Weight, _ color: NSColor, y: CGFloat, x: CGFloat = 0, w: CGFloat = 1280) {
    let p = NSMutableParagraphStyle()
    p.alignment = .center
    let attrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: size, weight: weight), .foregroundColor: color, .paragraphStyle: p]
    NSAttributedString(string: s, attributes: attrs).draw(in: NSRect(x: x, y: y, width: w, height: size * 1.4))
}

NSGraphicsContext.saveGraphicsState()
let icon = NSRect(x: 520, y: 60, width: 240, height: 240)
NSBezierPath(roundedRect: icon, xRadius: 54, yRadius: 54).addClip()
NSImage(contentsOfFile: a[1])!.draw(in: icon, from: .zero, operation: .copy, fraction: 1, respectFlipped: true, hints: nil)
NSGraphicsContext.restoreGraphicsState()

text("Gimlistream", 64, .bold, .white, y: 320)
text("DJI FPV Goggles V1/V2 live view", 24, .regular, .gray, y: 410)

let buttons = [("Live view", "Enter"), ("Record", "R"), ("Recordings", "O"), ("Diagnostics", "D"), ("Quit", "Esc")]
for (i, (label, key)) in buttons.enumerated() {
    let r = NSRect(x: 80 + i * 230, y: 500, width: 200, height: 100)
    (i == 0 ? NSColor.systemBlue : i == 1 ? NSColor.systemRed : NSColor(white: 0.18, alpha: 1)).setFill()
    NSBezierPath(roundedRect: r, xRadius: 18, yRadius: 18).fill()
    text(label, 28, .semibold, .white, y: r.minY + 16, x: r.minX, w: r.width)
    text(key, 20, .regular, NSColor(white: 1, alpha: 0.7), y: r.minY + 60, x: r.minX, w: r.width)
}
text("Connect the goggles with a USB-C to USB-A adapter (C-to-C cables don't work)", 18, .regular, .darkGray, y: 650)

NSGraphicsContext.current = nil
let px = rep.bitmapData!
var rgb = Data(capacity: W * H * 3)
for i in 0..<(W * H) { rgb.append(contentsOf: [px[i * 4], px[i * 4 + 1], px[i * 4 + 2]]) }
try! rgb.write(to: URL(fileURLWithPath: a[2]))

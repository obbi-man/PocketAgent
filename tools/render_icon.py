#!/usr/bin/env python3
"""PNG-версии иконки (легаси mipmap, 512 px для магазина, превью) из того же контура ✦, что и векторная иконка.
Запуск: python3 tools/render_icon.py app/src/main/res <папка_для_превью>"""
import os, sys
from PIL import Image, ImageDraw, ImageFilter

STAR = [(11,1.5),[(11.7,7.6),(14.4,10.3),(20.5,11)],[(14.4,11.7),(11.7,14.4),(11,20.5)],[(10.3,14.4),(7.6,11.7),(1.5,11)],[(7.6,10.3),(10.3,7.6),(11,1.5)]]
SMALL = [(19,15.5),[(19.3,18.2),(20.3,19.2),(23,19.5)],[(20.3,19.8),(19.3,20.8),(19,23.5)],[(18.7,20.8),(17.7,19.8),(15,19.5)],[(17.7,19.2),(18.7,18.2),(19,15.5)]]
BG = (0x0E, 0x0E, 0x10); C1 = (0x8B, 0x5C, 0xF6); C2 = (0xEC, 0x48, 0x99)

def poly(shape, f):
    pts = [f(shape[0])]; p0 = shape[0]
    for c1, c2, p3 in shape[1:]:
        for i in range(1, 41):
            t = i / 40; u = 1 - t
            pts.append(f((u**3*p0[0] + 3*u*u*t*c1[0] + 3*u*t*t*c2[0] + t**3*p3[0],
                          u**3*p0[1] + 3*u*u*t*c1[1] + 3*u*t*t*c2[1] + t**3*p3[1])))
        p0 = p3
    return pts

def star_mask(S, scale, tx, ty):
    k = S / 108.0
    f = lambda p: ((p[0]*scale + tx) * k, (p[1]*scale + ty) * k)
    m = Image.new("L", (S, S), 0); d = ImageDraw.Draw(m)
    d.polygon(poly(STAR, f), fill=255); d.polygon(poly(SMALL, f), fill=255)
    return m

def diag_gradient(S, box):
    x0, y0, x1, y1 = box; span = (x1 - x0) + (y1 - y0)
    g = Image.new("L", (S, S)); px = g.load()
    for y in range(y0, y1):
        for x in range(x0, x1):
            px[x, y] = max(0, min(255, int(((x - x0) + (y - y0)) / span * 255)))
    return g

_grad = {}
def render(size, shape="square", scale=2.7, tx=20.9, ty=20.25):
    S = min(size * 4, 1024)
    img = Image.new("RGBA", (S, S), BG + (255,))
    glow = Image.new("L", (S, S), 0); ImageDraw.Draw(glow).ellipse([S*0.2, S*0.2, S*0.8, S*0.8], fill=255)
    glow = glow.filter(ImageFilter.GaussianBlur(S*0.13)).point(lambda v: int(v*0.40))
    img.paste(Image.new("RGBA", (S, S), C1 + (255,)), (0, 0), glow)
    mask = star_mask(S, scale, tx, ty)
    halo = mask.filter(ImageFilter.GaussianBlur(S*0.035)).point(lambda v: int(v*0.55))
    img.paste(Image.new("RGBA", (S, S), (0xB0, 0x5C, 0xF0, 255)), (0, 0), halo)
    key = (S, scale, tx, ty)
    if key not in _grad: _grad[key] = diag_gradient(S, mask.getbbox())
    grad = Image.composite(Image.new("RGBA", (S, S), C2 + (255,)), Image.new("RGBA", (S, S), C1 + (255,)), _grad[key])
    img.paste(grad, (0, 0), mask)
    if shape != "square":
        m = Image.new("L", (S, S), 0); d = ImageDraw.Draw(m)
        if shape == "circle": d.ellipse([0, 0, S-1, S-1], fill=255)
        else: d.rounded_rectangle([0, 0, S-1, S-1], radius=int(S*0.22), fill=255)
        img.putalpha(m)
    return img.resize((size, size), Image.LANCZOS)

BIG = dict(scale=3.4, tx=12.35, ty=11.5)   # легаси/магазин: без обрезки адаптивной маской звезда крупнее

if __name__ == "__main__":
    res, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for d, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        os.makedirs(f"{res}/mipmap-{d}", exist_ok=True)
        render(px, "rounded", **BIG).save(f"{res}/mipmap-{d}/ic_launcher.png")
        render(px, "circle", **BIG).save(f"{res}/mipmap-{d}/ic_launcher_round.png")
    render(512, "square", **BIG).save(f"{out}/store_icon_512.png")
    prev = Image.new("RGBA", (1300, 420), (240, 240, 245, 255))
    items = [render(256, "square", **BIG), render(256, "circle"), render(256, "rounded")]
    th = Image.new("RGBA", (256, 256), (0, 0, 0, 0)); ImageDraw.Draw(th).ellipse([0, 0, 255, 255], fill=(220, 225, 255, 255))
    th.paste(Image.new("RGBA", (256, 256), (45, 55, 115, 255)), (0, 0), star_mask(1024, 2.7, 20.9, 20.25).resize((256, 256), Image.LANCZOS))
    items.append(th)
    d = ImageDraw.Draw(prev)
    from PIL import ImageFont
    try: font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", 22)
    except Exception: font = None
    for i, (im, lab) in enumerate(zip(items, ["Play (512 px)", "круглая маска", "скруглённая маска", "themed, Android 13+"])):
        prev.paste(im, (50 + i*310, 60), im)
        d.text((50 + i*310, 340), lab, fill=(50, 50, 60, 255), font=font)
    prev.save(f"{out}/icon_preview.png")
    print("ok")

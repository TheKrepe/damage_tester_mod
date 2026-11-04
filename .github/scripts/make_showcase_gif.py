"""Turns the gif_*.png frames of the showcase run into a GIF (and keeps the stills)."""
import glob, os, sys
from PIL import Image
src, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
frames = sorted(glob.glob(os.path.join(src, '*_gif_*.png')))
for f in sorted(glob.glob(os.path.join(src, '*showcase_*.png'))):
    name = f.split('showcase_', 1)[1]
    Image.open(f).convert('RGB').save(os.path.join(out, name), optimize=True)
if frames:
    imgs = [Image.open(f).convert('RGB').resize((640, 360), Image.LANCZOS) for f in frames]
    sheet = Image.new('RGB', (640, 360 * len(imgs)))
    for i, im in enumerate(imgs):
        sheet.paste(im, (0, 360 * i))
    pal = sheet.quantize(colors=255, method=Image.MEDIANCUT)
    q = [im.quantize(palette=pal, dither=Image.Dither.FLOYDSTEINBERG) for im in imgs]
    q[0].save(os.path.join(out, 'damage_tester_demo.gif'), save_all=True, append_images=q[1:], duration=50, loop=0, optimize=True)
print('frames:', len(frames))

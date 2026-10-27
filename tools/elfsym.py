"""Minimal ELF64 reader: print the build id, then the nearest preceding symbol for each
address given on the command line. No NDK needed."""
import struct
import sys

path = sys.argv[1]
addrs = [int(a, 16) for a in sys.argv[2:]]
data = open(path, "rb").read()

assert data[:4] == b"\x7fELF" and data[4] == 2, "not ELF64"
e_shoff, = struct.unpack_from("<Q", data, 0x28)
e_shentsize, e_shnum, e_shstrndx = struct.unpack_from("<HHH", data, 0x3A)

sections = []
for i in range(e_shnum):
    off = e_shoff + i * e_shentsize
    name, stype, flags, addr, offset, size, link, info, align, entsize = struct.unpack_from(
        "<IIQQQQIIQQ", data, off
    )
    sections.append(dict(name=name, type=stype, addr=addr, offset=offset, size=size,
                         link=link, entsize=entsize))

shstr = sections[e_shstrndx]


def sname(s):
    start = shstr["offset"] + s["name"]
    end = data.index(b"\0", start)
    return data[start:end].decode()


for s in sections:
    if s["type"] == 7 and sname(s) == ".note.gnu.build-id":
        o = s["offset"]
        namesz, descsz, _ = struct.unpack_from("<III", data, o)
        desc = data[o + 12 + ((namesz + 3) & ~3):][:descsz]
        print("build id:", desc.hex())

syms = []
for s in sections:
    if s["type"] not in (2, 11):  # SYMTAB, DYNSYM
        continue
    strtab = sections[s["link"]]
    count = s["size"] // s["entsize"]
    for i in range(count):
        o = s["offset"] + i * s["entsize"]
        st_name, st_info, st_other, st_shndx, st_value, st_size = struct.unpack_from(
            "<IBBHQQ", data, o
        )
        if st_value == 0 or (st_info & 0xF) != 2:  # STT_FUNC only
            continue
        start = strtab["offset"] + st_name
        end = data.index(b"\0", start)
        syms.append((st_value, st_size, data[start:end].decode(errors="replace")))

syms.sort()
print("function symbols:", len(syms))
for a in addrs:
    hit = None
    lo, hi = 0, len(syms) - 1
    while lo <= hi:
        mid = (lo + hi) // 2
        if syms[mid][0] <= a:
            hit = syms[mid]
            lo = mid + 1
        else:
            hi = mid - 1
    if hit is None:
        print(f"{a:#x}  <before first symbol>")
    else:
        within = "" if hit[1] == 0 or a < hit[0] + hit[1] else "  (PAST END, nearest only)"
        print(f"{a:#x}  {hit[2]}+{a - hit[0]:#x}{within}")

"""Convert the Trick Guide's demo set (DIST_TrickGuide.rx2) for the mod.

Reads <trickguide>/park/source (written by prepare_trick_guide.py) and
writes beside it:
- park/park.json: one entry per mesh: its diffuse texture, whether the
  baked lightmap applies, and its vertex range in park.bin;
- park/park.bin: little-endian float32 triangles, 7 floats per vertex
  (x, y, z, u, v, lightmap u, lightmap v), in the set's own space, which is
  also the space the demo clips are authored in (metres, y up);
- park/textures/*.rgba: raw RGBA8, as TrickHud.uploadRaw reads them.

Run on its own to convert a guide extracted before this step existed:
    python trick_guide_park.py <assets>/private/trickguide
"""
from pathlib import Path
import json
import struct
import sys

HERE = Path(__file__).resolve().parent
VENDOR = HERE / 'vendor'


def _imports():
    for path in (VENDOR / 'utt', VENDOR / 'university/tools/vanilla_map_extraction/tools', HERE, HERE.parent):
        if str(path) not in sys.path:
            sys.path.insert(0, str(path))


def convert(trickguide: Path):
    _imports()
    import numpy as np
    import mdl_parser
    import rx2_parser
    import prepare_hawaiian_dream as prep
    from retail_lightmap_uv import decode_lightmap_uvs
    from retail_texture_decode import B5G6R5_FORMAT_ID, decode_b5g6r5
    from tools.asset_pipeline.backdrop import texture_groups
    from tools.asset_pipeline.sky import _texture

    source = trickguide / 'park' / 'source'
    raw = (source / 'dist_trickguide.rx2').read_bytes()
    textures = rx2_parser.parse_rx2((source / 'dist_trickguide_textures.rx2').read_bytes())
    model = mdl_parser.parse_rx2(raw)
    groups, bindings = prep._bind_material_groups_by_guid(
        raw, prep._group_material_parameters(model.materials), len(model.meshes),
        allow_import_order_fallback=False)
    table = rx2_parser.RX2File(raw)
    table.parse()
    channels = texture_groups(raw, table, prep.RETAIL_TEXTURE_CHANNELS)

    out = trickguide / 'park'
    (out / 'textures').mkdir(parents=True, exist_ok=True)
    written = {}

    def export(guid):
        if guid in written:
            return written[guid]
        texture = _texture(textures, guid)
        rgba = texture.rgba
        if texture.fmt_id == B5G6R5_FORMAT_ID:
            rgba = decode_b5g6r5(textures.data[texture.data_offset:texture.data_offset + texture.buffer_size],
                                 texture.width, texture.height)
        name = f'textures/{guid:016x}.rgba'
        (out / name).write_bytes(bytes(rgba))
        written[guid] = dict(file='park/' + name, width=texture.width, height=texture.height)
        return written[guid]

    meshes, data, first = [], bytearray(), 0
    lightmap = None
    for i, mesh in enumerate(model.meshes):
        material = prep._material_metadata(groups, i)
        roles = channels[bindings[i]['group_index']]
        if 'diffuse' not in roles or mesh.uvs is None:
            print(f'Trick guide set: skipping untextured mesh {i}', flush=True)
            continue
        vertices = np.asarray(mesh.vertices, dtype=np.float32)
        uvs = np.asarray(mesh.uvs, dtype=np.float32)
        faces = np.asarray(mesh.faces, dtype=np.int64)
        lm = None
        if 'lightmap' in roles:
            decoded = decode_lightmap_uvs(raw, vertex_buffer_offset=mesh.source_offsets['vertex_buffer'],
                                          vertex_count=mesh.vertex_count, vertex_stride=mesh.vertex_stride,
                                          attributes=mesh.attributes)
            if decoded is not None:
                # Static world shaders sample abs(uv): the signs carry tangent handedness.
                lm = np.abs(np.asarray(decoded.values, dtype=np.float32))
                texture = export(roles['lightmap'])
                if lightmap not in (None, texture['file']):
                    raise ValueError('Trick guide set uses more than one lightmap')
                lightmap = texture['file']
        if lm is None:
            lm = np.zeros_like(uvs)
        corners = faces.reshape(-1)
        block = np.concatenate([vertices[corners], uvs[corners], lm[corners]], axis=1).astype('<f4')
        data += block.tobytes()
        meshes.append(dict(name=groups[i]['Name'][0].split('_0x')[0], shader=material['shader_name'],
                           texture=export(roles['diffuse']), lit=bool(np.any(lm)),
                           sky=material['shader_name'].startswith('sky'), first=first, count=len(corners)))
        first += len(corners)

    (out / 'park.bin').write_bytes(bytes(data))
    lightmap_entry = next((t for t in written.values() if t['file'] == lightmap), None)
    (out / 'park.json').write_text(json.dumps(dict(version=1, floats_per_vertex=7, lightmap=lightmap_entry,
                                                   meshes=meshes), indent=1) + '\n', encoding='utf-8')
    return len(meshes), first


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    meshes, vertices = convert(Path(sys.argv[1]))
    print(f'Trick guide set: {meshes} meshes, {vertices} vertices')

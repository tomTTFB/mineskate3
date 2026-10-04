#version 150

// Skate 3's APT colour transform: texture * multiply + add (hud_render.wgsl).
uniform sampler2D Sampler0;
uniform vec4 ColorMultiply;
uniform vec4 ColorAdd;

in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = clamp(texture(Sampler0, texCoord0) * ColorMultiply + ColorAdd, 0.0, 1.0);
    if (color.a <= 0.0) {
        discard;
    }
    fragColor = color;
}

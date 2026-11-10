#version 150

// 顶点着色器 —— 全屏四边形。
// Position.xy 是输出 target 的像素坐标（0..OutSize），
// ProjMat 由原版 PostChain 传入，把像素坐标映射到 NDC。
// texCoord 必须和 Position 用同一个尺寸归一化，否则画面会被裁切放大。

in vec4 Position;

uniform mat4 ProjMat;
uniform vec2 OutSize;

out vec2 texCoord;

void main() {
    vec4 outPos = ProjMat * vec4(Position.xy, 0.0, 1.0);
    gl_Position = vec4(outPos.xy, 0.2, 1.0);
    texCoord = Position.xy / OutSize;
}

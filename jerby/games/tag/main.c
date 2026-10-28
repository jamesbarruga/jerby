/* Jerby Tag - an original, freely redistributable two-player SNES game.
 * Move with the D-pad and press A near the other player to tag.
 * Copyright (c) 2026 Jerby contributors. SPDX-License-Identifier: MIT
 */
#include <snes.h>
#include <snes/text.h>
#include "res/player.inc"

static s16 px[2] = { 36, 190 };
static s16 py[2] = { 112, 112 };
static u8 score[2] = { 0, 0 };
static u8 tagger = 0;
static char score_line[] = "P1 0               P2 0";

static void draw_hud(void) {
    score_line[3] = (char)('0' + score[0]);
    score_line[22] = (char)('0' + score[1]);
    textPrintAt(3, 4, score_line);
    textPrintAt(6, 6, tagger == 0 ? "P1 CHASES P2" : "P2 CHASES P1");
}

static u8 close_enough(void) {
    s16 dx = px[0] - px[1];
    s16 dy = py[0] - py[1];
    if (dx < 0) dx = -dx;
    if (dy < 0) dy = -dy;
    return dx < 28 && dy < 28;
}

int main(void) {
    u8 p;
    textModeInit();
    setColor(0, RGB(2, 5, 8));
    setColor(1, RGB(31, 30, 12));
    dmaCopyVram(player_tiles, 0x2100, player_tiles_end - player_tiles);
    dmaCopyCGram(player_pal, OBJ_CGRAM_BASE, PALETTE_16_SIZE);
    oamInit(OBJ_SIZE8_L32, 1);
    oamSet(0, px[0], py[0], 0x0010, 0, 3, 0);
    oamSet(1, px[1], py[1], 0x0010, 0, 3, OBJ_FLIPX);
    oamSetSize(0, OBJ_LARGE);
    oamSetSize(1, OBJ_LARGE);
    setMainScreen(LAYER_BG1 | LAYER_OBJ);
    textPrintAt(10, 1, "JERBY TAG!");
    draw_hud();
    textPrintAt(4, 8, "MOVE WITH THE D-PAD");
    textPrintAt(4, 25, "PRESS A NEAR FRIEND TO TAG");
    WaitForVBlank();
    setScreenOn();

    while (1) {
        WaitForVBlank();
        for (p = 0; p < 2; p++) {
            u16 held = padHeld(p);
            if ((held & KEY_LEFT) && px[p] > 4) px[p] -= 2;
            if ((held & KEY_RIGHT) && px[p] < 220) px[p] += 2;
            if ((held & KEY_UP) && py[p] > 75) py[p] -= 2;
            if ((held & KEY_DOWN) && py[p] < 190) py[p] += 2;
            oamSetXY(p, px[p], py[p]);
        }
        if ((padPressed(tagger) & KEY_A) && close_enough()) {
            if (score[tagger] < 9) score[tagger]++;
            tagger ^= 1;
            px[0] = 36; px[1] = 190;
            py[0] = py[1] = 112;
            draw_hud();
        }
    }
    return 0;
}

/* Jerby Catch: original two-player SNES homebrew. SPDX-License-Identifier: MIT */
#include <snes.h>
#include <snes/text.h>
#include "res/player.inc"

static s16 px[2] = { 36, 190 };
static u8 score[2] = { 0, 0 };
static u16 rng = 1234;
static s16 star_x = 120;
static s16 star_y = 64;
static char score_line[] = "P1 0               P2 0";

static void draw_score(void) {
    score_line[3] = (char)('0' + score[0]);
    score_line[22] = (char)('0' + score[1]);
    textPrintAt(3, 4, score_line);
}

static void move_star(void) {
    rng = (u16)(rng * 109 + 89);
    star_x = (s16)(24 + (rng % 208));
    star_y = 64;
}

int main(void) {
    u8 p;
    textModeInit();
    setColor(0, RGB(2, 5, 8));
    setColor(1, RGB(31, 30, 12));
    dmaCopyVram(player_tiles, 0x2100, player_tiles_end - player_tiles);
    dmaCopyCGram(player_pal, OBJ_CGRAM_BASE, PALETTE_16_SIZE);
    oamInit(OBJ_SIZE8_L32, 1);
    oamSet(0, px[0], 172, 0x0010, 0, 3, 0);
    oamSet(1, px[1], 172, 0x0010, 0, 3, OBJ_FLIPX);
    oamSetSize(0, OBJ_LARGE);
    oamSetSize(1, OBJ_LARGE);
    setMainScreen(LAYER_BG1 | LAYER_OBJ);
    textPrintAt(10, 1, "JERBY CATCH!");
    draw_score();
    textPrintAt(3, 6, "CATCH THE FALLING STARS");
    textPrintAt(3, 25, "MOVE LEFT AND RIGHT TO CATCH");
    textPrintAt(star_x / 8, star_y / 8, "*");
    WaitForVBlank();
    setScreenOn();

    while (1) {
        s16 old_x, old_y;
        WaitForVBlank();
        for (p = 0; p < 2; p++) {
            u16 held = padHeld(p);
            if ((held & KEY_LEFT) && px[p] > 2) px[p] -= 3;
            if ((held & KEY_RIGHT) && px[p] < 222) px[p] += 3;
            oamSetXY(p, px[p], 172);
        }
        if ((padPressed(0) | padPressed(1)) & KEY_START) {
            score[0] = score[1] = 0;
            draw_score();
        }
        old_x = star_x / 8;
        old_y = star_y / 8;
        star_y += 2;
        if (star_y >= 178) {
            for (p = 0; p < 2; p++) {
                s16 distance = star_x - (px[p] + 16);
                if (distance < 0) distance = -distance;
                if (distance < 20) {
                    score[p] = (u8)((score[p] + 1) % 10);
                    draw_score();
                    break;
                }
            }
            textPrintAt(old_x, old_y, " ");
            move_star();
        } else if (old_x != star_x / 8 || old_y != star_y / 8)
            textPrintAt(old_x, old_y, " ");
        textPrintAt(star_x / 8, star_y / 8, "*");
    }
    return 0;
}

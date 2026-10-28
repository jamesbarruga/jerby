/* Jerby Pong: original two-player SNES homebrew. SPDX-License-Identifier: MIT */
#include <snes.h>
#include <snes/text.h>
#include "res/player.inc"

static s16 py[2] = { 112, 112 };
static s16 ball_x = 128;
static s16 ball_y = 112;
static s16 velocity_x = 2;
static s16 velocity_y = 2;
static u8 score[2] = { 0, 0 };
static char score_line[] = "P1 0               P2 0";

static void draw_score(void) {
    score_line[3] = (char)('0' + score[0]);
    score_line[22] = (char)('0' + score[1]);
    textPrintAt(3, 4, score_line);
}

static void reset_ball(s16 direction) {
    ball_x = 128;
    ball_y = 112;
    velocity_x = direction;
    velocity_y = -velocity_y;
}

static u8 near_paddle(s16 ball, s16 paddle) {
    s16 distance = ball - (paddle + 16);
    if (distance < 0) distance = -distance;
    return distance < 25;
}

int main(void) {
    u8 p;
    textModeInit();
    setColor(0, RGB(2, 5, 8));
    setColor(1, RGB(31, 30, 12));
    dmaCopyVram(player_tiles, 0x2100, player_tiles_end - player_tiles);
    dmaCopyCGram(player_pal, OBJ_CGRAM_BASE, PALETTE_16_SIZE);
    oamInit(OBJ_SIZE8_L32, 1);
    oamSet(0, 14, py[0], 0x0010, 0, 3, 0);
    oamSet(1, 210, py[1], 0x0010, 0, 3, OBJ_FLIPX);
    oamSetSize(0, OBJ_LARGE);
    oamSetSize(1, OBJ_LARGE);
    setMainScreen(LAYER_BG1 | LAYER_OBJ);
    textPrintAt(11, 1, "JERBY PONG!");
    draw_score();
    textPrintAt(4, 6, "BOUNCE IT PAST YOUR FRIEND");
    textPrintAt(5, 25, "MOVE UP AND DOWN TO BLOCK");
    textPrintAt(ball_x / 8, ball_y / 8, "O");
    WaitForVBlank();
    setScreenOn();

    while (1) {
        s16 old_x, old_y;
        WaitForVBlank();
        for (p = 0; p < 2; p++) {
            u16 held = padHeld(p);
            if ((held & KEY_UP) && py[p] > 72) py[p] -= 3;
            if ((held & KEY_DOWN) && py[p] < 180) py[p] += 3;
            oamSetXY(p, p == 0 ? 14 : 210, py[p]);
        }
        if ((padPressed(0) | padPressed(1)) & KEY_START) {
            score[0] = score[1] = 0;
            draw_score();
        }
        old_x = ball_x / 8;
        old_y = ball_y / 8;
        ball_x += velocity_x;
        ball_y += velocity_y;
        if (ball_y < 70 || ball_y > 190) velocity_y = -velocity_y;
        if (ball_x <= 38) {
            if (near_paddle(ball_y, py[0])) velocity_x = 2;
            else {
                score[1] = (u8)((score[1] + 1) % 10);
                draw_score();
                reset_ball(2);
            }
        } else if (ball_x >= 215) {
            if (near_paddle(ball_y, py[1])) velocity_x = -2;
            else {
                score[0] = (u8)((score[0] + 1) % 10);
                draw_score();
                reset_ball(-2);
            }
        }
        if (old_x != ball_x / 8 || old_y != ball_y / 8)
            textPrintAt(old_x, old_y, " ");
        textPrintAt(ball_x / 8, ball_y / 8, "O");
    }
    return 0;
}

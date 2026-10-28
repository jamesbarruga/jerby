/* Jerby Dash - an original, freely redistributable two-player SNES game.
 * Race your friend by alternating A and B.  The two controllers are read
 * independently, so RetroArch netplay can supply player two over LAN.
 * Copyright (c) 2026 Jerby contributors. SPDX-License-Identifier: MIT
 */
#include <snes.h>
#include <snes/text.h>
#include "res/player.inc"

#define START_Y 172
#define FINISH_Y 70
#define STEP 6

static s16 racer_y[2] = { START_Y, START_Y };
static u8 previous_button[2] = { 0, 0 };
static u8 score[2] = { 0, 0 };
static u8 winner = 0;
static char score_line[] = "P1 0               P2 0";

static void draw_score(void) {
    score_line[3] = (char)('0' + score[0]);
    score_line[22] = (char)('0' + score[1]);
    textPrintAt(3, 4, score_line);
}

static void reset_round(void) {
    racer_y[0] = START_Y;
    racer_y[1] = START_Y;
    previous_button[0] = previous_button[1] = 0;
    winner = 0;
    textPrintAt(7, 6, "                ");
    textPrintAt(4, 25, "ALTERNATE A AND B TO DASH");
    oamSetXY(0, 46, racer_y[0]);
    oamSetXY(1, 174, racer_y[1]);
}

int main(void) {
    u8 p;
    textModeInit();
    setColor(0, RGB(2, 5, 8));
    setColor(1, RGB(31, 30, 12));
    dmaCopyVram(player_tiles, 0x2100, player_tiles_end - player_tiles);
    dmaCopyCGram(player_pal, OBJ_CGRAM_BASE, PALETTE_16_SIZE);
    oamInit(OBJ_SIZE8_L32, 1);
    oamSet(0, 46, START_Y, 0x0010, 0, 3, 0);
    oamSet(1, 174, START_Y, 0x0010, 0, 3, OBJ_FLIPX);
    oamSetSize(0, OBJ_LARGE);
    oamSetSize(1, OBJ_LARGE);
    setMainScreen(LAYER_BG1 | LAYER_OBJ);

    textPrintAt(10, 1, "JERBY DASH!");
    draw_score();
    textPrintAt(2, 8, "============================");
    textPrintAt(6, 9, "FINISH");
    textPrintAt(22, 9, "FINISH");
    textPrintAt(7, 24, "PLAYER 1    PLAYER 2");
    textPrintAt(4, 25, "ALTERNATE A AND B TO DASH");
    WaitForVBlank();
    setScreenOn();

    while (1) {
        WaitForVBlank();
        if (winner) {
            if ((padPressed(0) | padPressed(1)) & KEY_START)
                reset_round();
            continue;
        }
        for (p = 0; p < 2; p++) {
            u16 buttons = padPressed(p);
            u8 next = 0;
            if (buttons & KEY_A) next = 1;
            else if (buttons & KEY_B) next = 2;
            if (next && next != previous_button[p]) {
                racer_y[p] -= STEP;
                previous_button[p] = next;
                oamSetXY(p, p == 0 ? 46 : 174, racer_y[p]);
                if (racer_y[p] <= FINISH_Y) {
                    winner = (u8)(p + 1);
                    if (score[p] < 9) score[p]++;
                    draw_score();
                    textPrintAt(7, 6, p == 0 ? "PLAYER 1 WINS!" : "PLAYER 2 WINS!");
                    textPrintAt(4, 25, "PRESS START TO RACE AGAIN");
                    break;
                }
            }
        }
    }
    return 0;
}

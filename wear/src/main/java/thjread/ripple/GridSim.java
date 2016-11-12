package thjread.ripple;

import android.graphics.Bitmap;
import android.util.Log;

/**
 * Created by tread on 12/11/16.
 */

public class GridSim {
    private int num_x;
    private int num_y;

    public void initGrid(int num_x, int num_y) {
        this.num_x = num_x;
        this.num_y = num_y;
    }

    public void setRecordInit(float[][][] record, Bitmap bitmap) {
        for (int y=0; y<num_y; ++y) {
            for (int x=0; x<num_x; ++x) {
                record[0][y][x] = record[1][y][x] = ((float) (bitmap.getPixel(x, y) & 0xff))*10/255;
            }
        }
    }

    public void simulateGrid(float[][][] array, int index, float diff) {
        for (int y=0; y<num_y; ++y) {
            for (int x=0; x<num_x; ++x) {
                float here = array[index-1][y][x];
                float old = array[index-2][y][x];

                float left = x == 0 ? here : array[index-1][y][x-1];
                float right = x == num_x-1 ? here : array[index-1][y][x+1];
                float up = y == 0 ? here : array[index-1][y-1][x];
                float down = y == num_y-1 ? here : array[index-1][y+1][x];
                float lagrangian = (right-here)-(here-left) + (down-here)-(here-up);

                array[index][y][x] = 0.35f*lagrangian*diff + 2*here - old;
            }
        }
    }
}

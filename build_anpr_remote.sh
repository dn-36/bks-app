#!/bin/sh
set -eu

ROOT=/root/luckfox-build
SRC=$ROOT/luckfox-board-linux-src/anpr_controller/src/main.cc
OUT=$ROOT/anpr_controller
SDK=$ROOT/luckfox-pico-sdk-sparse
RKNN=$ROOT/luckfox_pico_rknn_example
TC=$SDK/tools/linux/toolchain/arm-rockchip830-linux-uclibcgnueabihf/bin/arm-rockchip830-linux-uclibcgnueabihf-g++

OPENCV_EXTRA_LIB=$SDK/project/app/rk_smart_door/smart_door/common/face/algo/lib/opencv-linux-armhf1106/lib
OPENCV_EXTRA_DEPS=$SDK/project/app/rk_smart_door/smart_door/common/face/algo/lib/opencv-linux-armhf1106/lib2
OPENCV_INCLUDE=$SDK/project/app/rk_smart_door/smart_door/common/face/algo_dual_ir/lib/opencv-linux-armhf/include
RKNN_LIB=$RKNN/lib/uclibc
ROCKIT_LIB=$SDK/media/rockit/rockit/lib/lib32
RTSP_LIB=$SDK/media/common_algorithm/common_algorithm/misc/lib/arm-rockchip830-linux-uclibcgnueabihf
MPP_LIB=$SDK/media/mpp/release_mpp_rv1106_arm-rockchip830-linux-uclibcgnueabihf/lib
RGA_LIB=$SDK/media/rga/release_rga_rv1106_arm-rockchip830-linux-uclibcgnueabihf/lib
THIRD_LIB=$SDK/project/app/component/third_libs/lib/arm-rockchip830-linux-uclibcgnueabihf

"$TC" -std=c++17 -O2 -fPIC \
  -DRV1106_1103 \
  -I$ROOT/luckfox-board-linux-src/anpr_controller/src \
  -I$OPENCV_INCLUDE \
  -I$RKNN/include \
  -I$RKNN/include/rknn \
  -I$SDK/media/common_algorithm/common_algorithm/misc/include \
  -I$SDK/media/isp/release_camera_engine_rkaiq_rv1106_arm-rockchip830-linux-uclibcgnueabihf/rkisp_demo/demo \
  -I$SDK/media/isp/release_camera_engine_rkaiq_rv1106_arm-rockchip830-linux-uclibcgnueabihf/rkisp_demo/demo/sample \
  -I$SDK/media/rockit/rockit/mpi/sdk/include \
  -I$SDK/media/mpp/release_mpp_rv1106_arm-rockchip830-linux-uclibcgnueabihf/include/rockchip \
  "$SRC" \
  -L$OPENCV_EXTRA_LIB \
  -L$OPENCV_EXTRA_DEPS \
  -L$RKNN_LIB \
  -L$ROCKIT_LIB \
  -L$RTSP_LIB \
  -L$MPP_LIB \
  -L$RGA_LIB \
  -L$THIRD_LIB \
  -Wl,-rpath,/oem/usr/lib:/userdata/anpr \
  -Wl,--start-group \
  -lopencv_videoio \
  -lopencv_imgcodecs \
  -lopencv_highgui \
  -lopencv_video \
  -lopencv_photo \
  -lopencv_features2d \
  -lopencv_imgproc \
  -lopencv_core \
  -lcarotene \
  -ltegra_hal \
  -llibjpeg-turbo \
  -llibopenjp2 \
  -llibpng \
  -llibtiff \
  -llibwebp \
  -littnotify \
  -lzlib \
  -lrknnmrt \
  -lrtsp \
  -lrockit \
  -lrockchip_mpp \
  -lrga \
  -ljpeg \
  -lpng \
  -lz \
  -lpthread \
  -ldl \
  -lm \
  -Wl,--end-group \
  -o "$OUT"

file "$OUT"
ls -lh "$OUT"

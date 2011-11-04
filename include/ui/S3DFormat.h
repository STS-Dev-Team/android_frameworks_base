/*
 * Copyright (C) 2011 Texas Instruments Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef UI_S3DFORMAT_H
#define UI_S3DFORMAT_H

#include <sys/types.h>

#ifdef __cplusplus
namespace android {
#endif
//These define how the content is layed out in the buffer
//When describing a S3D display these are defined in relation to the panel
//native scan order
enum S3DLayoutType {
    eMono           = 0x0,
    eSideBySide     = 0x1,
    eTopBottom      = 0x2,
    eRowInterleaved = 0x4,
    eColInterleaved = 0x8
};

//This defines which view was drawn first
//eMono: Don't care
//eSideBySide - eLeftViewFirst = L | R , eRightViewFirst = R | L
//eTopBottom - eLeftViewFirst = _L_, eRightViewFirst = _R_
//                               R                      L
//eRowInterleaved - eLeftViewFirst = first row is the left view
//eRowInterleaved - eRightViewFirst = first row is the right view
//eColInterleaved - eLeftViewFirst = first column is the left view
//eColInterleaved - eRightViewFirst = first column is the right view
enum S3DLayoutOrder {
    eLeftViewFirst = 0x0,
    eRightViewFirst = 0x1,
};

//Sources can use this values to define if only one or both of the views
//can be rendered. Mostly used for pre-rendered content.
enum S3DRenderMode {
    eRenderLeft     = 0x0,
    eRenderRight    = 0x1,
    eRenderStereo   = 0x2,
};

#ifdef __cplusplus
};
#endif
#endif //UI_S3DFORMAT_H
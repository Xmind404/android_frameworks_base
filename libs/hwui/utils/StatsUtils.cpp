/*
 * Copyright 2024 The Android Open Source Project
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

#include "StatsUtils.h"

namespace android {
namespace uirenderer {

// Bitmap decode statistics (statsd atom IMAGE_DECODED) are not collected on this build: the
// atom was written for every decode in every app process, costing a system property lookup and
// a statsd socket write each time, for usage telemetry this ROM does not need.

void logBitmapDecode(const SkImageInfo&, bool) {}

void logBitmapDecode(const Bitmap&) {}

}  // namespace uirenderer
}  // namespace android

#include "rendera_native.h"

namespace rendera {

struct RefZone {
    int startX;
    int startY;
    int zoneW;
    int zoneH;
};

struct NativeTrack {
    int id = 0;
    float x = 0.0f;
    float y = 0.0f;
    float vx = 0.0f;
    float vy = 0.0f;
    float p00 = 100.0f;
    float p11 = 100.0f;
    float p22 = 250.0f;
    float p33 = 250.0f;
    float speed = 0.0f;
    bool isProjectile = false;
    int hits = 1;
    int misses = 0;
};

class RenderaPipeline {
public:
    int width;
    int height;
    int totalPixels;
    bool hasPreviousFrame = false;

    std::vector<uint8_t> currentFrame;
    std::vector<uint8_t> prevFrame;
    std::vector<uint8_t> diffFrame;

    // Edge & Gradient buffers
    std::vector<int16_t> gradMag;
    std::vector<uint8_t> gradDir;
    std::vector<uint8_t> edgeMap;

    // Hough accumulator
    int accScale = 2;
    int accW;
    int accH;
    std::vector<int> accumulator;

    // Kalman tracks
    int nextTrackId = 1;
    std::vector<NativeTrack> tracks;

    // Camera motion
    int motionDx = 0;
    int motionDy = 0;

    RenderaPipeline(int w, int h) : width(w), height(h), totalPixels(w * h) {
        currentFrame.resize(totalPixels, 0);
        prevFrame.resize(totalPixels, 0);
        diffFrame.resize(totalPixels, 0);
        gradMag.resize(totalPixels, 0);
        gradDir.resize(totalPixels, 0);
        edgeMap.resize(totalPixels, 0);

        accW = width / accScale;
        accH = height / accScale;
        accumulator.resize(accW * accH, 0);
    }

    void reset() {
        hasPreviousFrame = false;
        std::fill(currentFrame.begin(), currentFrame.end(), 0);
        std::fill(prevFrame.begin(), prevFrame.end(), 0);
        std::fill(diffFrame.begin(), diffFrame.end(), 0);
        tracks.clear();
        nextTrackId = 1;
        motionDx = 0;
        motionDy = 0;
    }

    // Phase 1: Global Motion Compensation via Sub-window SAD
    void computeGlobalMotion() {
        if (!hasPreviousFrame) {
            motionDx = 0;
            motionDy = 0;
            return;
        }

        const int maxShift = 6;
        RefZone zones[4] = {
            { static_cast<int>(width * 0.12f), static_cast<int>(height * 0.10f), static_cast<int>(width * 0.16f), static_cast<int>(height * 0.16f) },
            { static_cast<int>(width * 0.72f), static_cast<int>(height * 0.10f), static_cast<int>(width * 0.16f), static_cast<int>(height * 0.16f) },
            { static_cast<int>(width * 0.10f), static_cast<int>(height * 0.44f), static_cast<int>(width * 0.16f), static_cast<int>(height * 0.16f) },
            { static_cast<int>(width * 0.74f), static_cast<int>(height * 0.44f), static_cast<int>(width * 0.16f), static_cast<int>(height * 0.16f) }
        };

        int totalDx = 0;
        int totalDy = 0;
        int validZones = 0;

        for (const auto& z : zones) {
            int minSAD = 999999;
            int bestDx = 0;
            int bestDy = 0;
            int step = (z.zoneW <= 20) ? 1 : 2;

            for (int dy = -maxShift; dy <= maxShift; dy += 2) {
                for (int dx = -maxShift; dx <= maxShift; dx += 2) {
                    int sad = 0;
                    int count = 0;

                    for (int zy = 0; zy < z.zoneH; zy += step) {
                        int currY = z.startY + zy;
                        int prevY = currY + dy;
                        if (prevY < 0 || prevY >= height) continue;

                        int currRow = currY * width;
                        int prevRow = prevY * width;

                        for (int zx = 0; zx < z.zoneW; zx += step) {
                            int currX = z.startX + zx;
                            int prevX = currX + dx;
                            if (prevX < 0 || prevX >= width) continue;

                            int currLum = currentFrame[currRow + currX];
                            int prevLum = prevFrame[prevRow + prevX];
                            sad += std::abs(currLum - prevLum);
                            count++;
                        }
                    }

                    if (count >= 8) {
                        int normSad = sad / count;
                        if (normSad < minSAD) {
                            minSAD = normSad;
                            bestDx = dx;
                            bestDy = dy;
                        }
                    }
                }
            }

            if (minSAD < 45) {
                totalDx += bestDx;
                totalDy += bestDy;
                validZones++;
            }
        }

        if (validZones > 0) {
            motionDx = totalDx / validZones;
            motionDy = totalDy / validZones;
        } else {
            motionDx = 0;
            motionDy = 0;
        }
    }

    // Phase 2: Frame Difference with Motion Compensation
    void computeCompensatedDifference(int noiseFloor = 20) {
        for (int y = 0; y < height; ++y) {
            int prevY = std::clamp(y + motionDy, 0, height - 1);
            int currRow = y * width;
            int prevRow = prevY * width;

            for (int x = 0; x < width; ++x) {
                int prevX = std::clamp(x + motionDx, 0, width - 1);
                int currLum = currentFrame[currRow + x];
                int prevLum = prevFrame[prevRow + prevX];
                int delta = std::abs(currLum - prevLum);
                diffFrame[currRow + x] = (delta > noiseFloor) ? static_cast<uint8_t>(delta) : 0;
            }
        }
    }

    // Phase 3: Sobel Filter and Canny Edge Detection
    void computeCannyEdges(int lowThresh = 20, int highThresh = 50) {
        std::fill(gradMag.begin(), gradMag.end(), 0);
        std::fill(edgeMap.begin(), edgeMap.end(), 0);

        for (int y = 1; y < height - 1; ++y) {
            int rowAbove = (y - 1) * width;
            int rowCurr  = y * width;
            int rowBelow = (y + 1) * width;

            for (int x = 1; x < width - 1; ++x) {
                int p00 = currentFrame[rowAbove + x - 1];
                int p01 = currentFrame[rowAbove + x];
                int p02 = currentFrame[rowAbove + x + 1];
                int p10 = currentFrame[rowCurr  + x - 1];
                int p12 = currentFrame[rowCurr  + x + 1];
                int p20 = currentFrame[rowBelow + x - 1];
                int p21 = currentFrame[rowBelow + x];
                int p22 = currentFrame[rowBelow + x + 1];

                int gx = (-p00 + p02) + 2 * (-p10 + p12) + (-p20 + p22);
                int gy = (p00 + 2 * p01 + p02) - (p20 + 2 * p21 + p22);

                int mag = (std::abs(gx) + std::abs(gy)) >> 2;
                int idx = rowCurr + x;
                gradMag[idx] = static_cast<int16_t>(mag);

                int absGx = std::abs(gx);
                int absGy = std::abs(gy);
                uint8_t dir = 0;
                if (absGx > 2 * absGy) {
                    dir = 2; // Horizontal gradient (vertical edge)
                } else if (absGy > 2 * absGx) {
                    dir = 0; // Vertical gradient (horizontal edge)
                } else if ((gx > 0 && gy > 0) || (gx < 0 && gy < 0)) {
                    dir = 1; // 45°
                } else {
                    dir = 3; // 135°
                }
                gradDir[idx] = dir;
            }
        }

        // Non-Maximum Suppression
        for (int y = 2; y < height - 2; ++y) {
            int row = y * width;
            for (int x = 2; x < width - 2; ++x) {
                int idx = row + x;
                int mag = gradMag[idx];
                if (mag < lowThresh) continue;

                uint8_t dir = gradDir[idx];
                bool isLocalMax = true;
                if (dir == 2) {
                    isLocalMax = (mag >= gradMag[idx - 1] && mag >= gradMag[idx + 1]);
                } else if (dir == 0) {
                    isLocalMax = (mag >= gradMag[idx - width] && mag >= gradMag[idx + width]);
                } else if (dir == 1) {
                    isLocalMax = (mag >= gradMag[idx - width - 1] && mag >= gradMag[idx + width + 1]);
                } else {
                    isLocalMax = (mag >= gradMag[idx - width + 1] && mag >= gradMag[idx + width - 1]);
                }

                if (isLocalMax) {
                    edgeMap[idx] = (mag >= highThresh) ? 2 : 1;
                }
            }
        }
    }

    // Phase 4: Identify Active Player via 4:1 Aspect Ratio Health Bar
    bool findActivePlayerBar(float& outX, float& outY) {
        int minBarW = std::max(6, static_cast<int>(width * 0.08f));
        int maxBarW = std::max(16, static_cast<int>(width * 0.50f));
        int minBarH = std::max(2, static_cast<int>(height * 0.04f));
        int maxBarH = std::max(6, static_cast<int>(height * 0.25f));

        float bestDist = 999999.0f;
        bool found = false;
        float centerX = width / 2.0f;
        float centerY = height / 2.0f;

        for (int y = static_cast<int>(height * 0.08f); y < static_cast<int>(height * 0.90f); ++y) {
            bool inBar = false;
            int startX = 0;

            for (int x = static_cast<int>(width * 0.05f); x < static_cast<int>(width * 0.95f); ++x) {
                int idx = y * width + x;
                bool isEdge = (edgeMap[idx] == 2);

                if (isEdge && !inBar) {
                    inBar = true;
                    startX = x;
                } else if (!isEdge && inBar) {
                    inBar = false;
                    int spanW = x - startX;

                    if (spanW >= minBarW && spanW <= maxBarW) {
                        for (int testH = minBarH; testH <= maxBarH; ++testH) {
                            int bottomY = y + testH;
                            if (bottomY >= height) break;

                            int bottomHits = 0;
                            int samplePoints = 6;
                            int stepX = std::max(1, spanW / samplePoints);

                            for (int s = 1; s < samplePoints; ++s) {
                                int sx = startX + s * stepX;
                                if (sx < 0 || sx >= width) continue;
                                int bIdx = bottomY * width + sx;
                                bool hit = (edgeMap[bIdx] == 2) ||
                                           (bottomY > 0 && edgeMap[bIdx - width] == 2) ||
                                           (bottomY < height - 1 && edgeMap[bIdx + width] == 2);
                                if (hit) bottomHits++;
                            }

                            if (bottomHits >= 2) {
                                float ratio = static_cast<float>(spanW) / static_cast<float>(testH);
                                if (ratio >= 2.8f && ratio <= 5.5f) {
                                    float barCenterX = startX + spanW / 2.0f;
                                    float barCenterY = y + testH / 2.0f;
                                    float d = std::hypot(barCenterX - centerX, barCenterY - centerY);
                                    if (d < bestDist) {
                                        bestDist = d;
                                        outX = barCenterX;
                                        outY = barCenterY;
                                        found = true;
                                    }
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }

        return found;
    }

    // Phase 5: Hough Circular Transform for Projectiles & Brawlers
    void detectHoughCircles(std::vector<std::pair<float, float>>& outCircles, int minVoteThresh = 14) {
        outCircles.clear();
        std::fill(accumulator.begin(), accumulator.end(), 0);

        int minRadius = 6;
        int maxRadius = 18;

        for (int y = minRadius; y < height - minRadius; ++y) {
            int row = y * width;
            for (int x = minRadius; x < width - minRadius; ++x) {
                int idx = row + x;
                if (edgeMap[idx] == 2) {
                    uint8_t dir = gradDir[idx];
                    // Normal angles in radians
                    float angle1 = 0.0f;
                    float angle2 = 0.0f;
                    if (dir == 0) { // Vertical
                        angle1 = 1.570796f; angle2 = 4.712389f;
                    } else if (dir == 2) { // Horizontal
                        angle1 = 0.0f; angle2 = 3.141592f;
                    } else if (dir == 1) { // 45 deg
                        angle1 = 0.785398f; angle2 = 3.926991f;
                    } else { // 135 deg
                        angle1 = 2.356194f; angle2 = 5.497787f;
                    }

                    for (int r = minRadius; r <= maxRadius; r += 2) {
                        int c1x = static_cast<int>(x + r * std::cos(angle1)) / accScale;
                        int c1y = static_cast<int>(y + r * std::sin(angle1)) / accScale;
                        if (c1x >= 0 && c1x < accW && c1y >= 0 && c1y < accH) {
                            accumulator[c1y * accW + c1x]++;
                        }

                        int c2x = static_cast<int>(x + r * std::cos(angle2)) / accScale;
                        int c2y = static_cast<int>(y + r * std::sin(angle2)) / accScale;
                        if (c2x >= 0 && c2x < accW && c2y >= 0 && c2y < accH) {
                            accumulator[c2y * accW + c2x]++;
                        }
                    }
                }
            }
        }

        // Peak extraction
        for (int ay = 2; ay < accH - 2; ++ay) {
            int aRow = ay * accW;
            for (int ax = 2; ax < accW - 2; ++ax) {
                int votes = accumulator[aRow + ax];
                if (votes >= minVoteThresh) {
                    bool isMax = true;
                    for (int dy = -1; dy <= 1; ++dy) {
                        for (int dx = -1; dx <= 1; ++dx) {
                            if (dx == 0 && dy == 0) continue;
                            if (accumulator[(ay + dy) * accW + (ax + dx)] > votes) {
                                isMax = false;
                                break;
                            }
                        }
                        if (!isMax) break;
                    }

                    if (isMax) {
                        float cx = (ax * accScale) + (accScale / 2.0f);
                        float cy = (ay * accScale) + (accScale / 2.0f);
                        outCircles.push_back({cx, cy});
                    }
                }
            }
        }
    }

    // Phase 6: Kalman Trajectory Filter & Projectile Detection
    void updateKalmanTracks(const std::vector<std::pair<float, float>>& observations, float dtSec = 0.020f) {
        float dt = std::clamp(dtSec, 0.008f, 0.050f);

        // Predict
        for (auto& trk : tracks) {
            trk.x += trk.vx * dt;
            trk.y += trk.vy * dt;
            trk.p00 += 2.0f;
            trk.p11 += 2.0f;
            trk.p22 += 45.0f;
            trk.p33 += 45.0f;
        }

        std::vector<bool> matched(observations.size(), false);
        const float maxGateSq = 180.0f * 180.0f;

        // Nearest neighbor association
        for (auto& trk : tracks) {
            float bestDistSq = 999999.0f;
            int bestIdx = -1;

            for (size_t i = 0; i < observations.size(); ++i) {
                if (matched[i]) continue;
                float dx = observations[i].first - trk.x;
                float dy = observations[i].second - trk.y;
                float distSq = dx * dx + dy * dy;

                if (distSq < maxGateSq && distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestIdx = static_cast<int>(i);
                }
            }

            if (bestIdx >= 0) {
                matched[bestIdx] = true;
                float mx = observations[bestIdx].first;
                float my = observations[bestIdx].second;
                float yx = mx - trk.x;
                float yy = my - trk.y;

                float s00 = trk.p00 + 16.0f;
                float s11 = trk.p11 + 16.0f;
                float k00 = trk.p00 / s00;
                float k11 = trk.p11 / s11;

                trk.x += k00 * yx;
                trk.y += k11 * yy;

                if (trk.hits == 1 && dt > 0.001f) {
                    trk.vx = yx / dt;
                    trk.vy = yy / dt;
                } else {
                    float k20 = (trk.p22 * dt) / s00;
                    float k31 = (trk.p33 * dt) / s11;
                    trk.vx += k20 * yx;
                    trk.vy += k31 * yy;
                }

                trk.p00 *= (1.0f - k00);
                trk.p11 *= (1.0f - k11);
                trk.speed = std::hypot(trk.vx, trk.vy);
                trk.hits++;
                trk.misses = 0;
                trk.isProjectile = (trk.speed > 180.0f && trk.hits >= 2);
            } else {
                trk.misses++;
            }
        }

        // Spawn new tracks
        for (size_t i = 0; i < observations.size(); ++i) {
            if (!matched[i] && tracks.size() < 16) {
                NativeTrack newTrk;
                newTrk.id = nextTrackId++;
                newTrk.x = observations[i].first;
                newTrk.y = observations[i].second;
                newTrk.vx = 0.0f;
                newTrk.vy = 0.0f;
                newTrk.hits = 1;
                newTrk.misses = 0;
                tracks.push_back(newTrk);
            }
        }

        // Prune stale tracks
        tracks.erase(std::remove_if(tracks.begin(), tracks.end(), [](const NativeTrack& t) {
            return t.misses > 4;
        }), tracks.end());
    }
};

} // namespace rendera

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_vision_nativebridge_NativeRenderaPipeline_nativeInit(
    JNIEnv* env,
    jobject thiz,
    jint gridWidth,
    jint gridHeight
) {
    LOGI("NativeRenderaPipeline initialized: %dx%d", gridWidth, gridHeight);
    auto* pipeline = new rendera::RenderaPipeline(gridWidth, gridHeight);
    return reinterpret_cast<jlong>(pipeline);
}

JNIEXPORT jint JNICALL
Java_com_example_vision_nativebridge_NativeRenderaPipeline_nativeProcessFrame(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jbyteArray grayscaleBytes,
    jfloat screenWidth,
    jfloat screenHeight,
    jfloatArray outThreatData
) {
    if (!handle) return 0;
    auto* pipeline = reinterpret_cast<rendera::RenderaPipeline*>(handle);

    jsize len = env->GetArrayLength(grayscaleBytes);
    if (len < pipeline->totalPixels) return 0;

    jbyte* srcBytes = env->GetByteArrayElements(grayscaleBytes, nullptr);
    if (!srcBytes) return 0;

    std::memcpy(pipeline->currentFrame.data(), srcBytes, pipeline->totalPixels);
    env->ReleaseByteArrayElements(grayscaleBytes, srcBytes, JNI_ABORT);

    // 1. Global motion compensation
    pipeline->computeGlobalMotion();

    // 2. Motion compensated difference
    pipeline->computeCompensatedDifference(20);

    // 3. Canny edge detection
    pipeline->computeCannyEdges(20, 50);

    // 4. Find active player
    float playerGridX = pipeline->width / 2.0f;
    float playerGridY = pipeline->height / 2.0f;
    bool foundPlayer = pipeline->findActivePlayerBar(playerGridX, playerGridY);

    float playerWorldX = (playerGridX / pipeline->width) * screenWidth;
    float playerWorldY = (playerGridY / pipeline->height) * screenHeight;
    if (foundPlayer) {
        playerWorldY += (screenHeight * 0.045f); // Offset to feet/hitbox
    }

    // 5. Hough circles
    std::vector<std::pair<float, float>> circles;
    pipeline->detectHoughCircles(circles, 14);

    // 6. Kalman tracking
    pipeline->updateKalmanTracks(circles, 0.020f);

    // 7. Check for collision threat
    float threatLevel = 0.0f; // 0=None, 1=Warning, 2=Imminent, 3=Lethal
    float dodgeAngleDeg = -1.0f;
    float playerRadius = screenWidth * 0.042f;

    for (const auto& trk : pipeline->tracks) {
        if (!trk.isProjectile || trk.speed < 150.0f) continue;

        float projWorldX = (trk.x / pipeline->width) * screenWidth;
        float projWorldY = (trk.y / pipeline->height) * screenHeight;
        float projWorldVx = (trk.vx / pipeline->width) * screenWidth;
        float projWorldVy = (trk.vy / pipeline->height) * screenHeight;

        float rx = playerWorldX - projWorldX;
        float ry = playerWorldY - projWorldY;
        float dot = rx * projWorldVx + ry * projWorldVy;

        if (dot > 0.0f) { // Moving towards player
            float vSq = projWorldVx * projWorldVx + projWorldVy * projWorldVy;
            float tcpa = dot / vSq;

            if (tcpa > 0.0f && tcpa < 0.40f) {
                float cpaX = projWorldX + projWorldVx * tcpa;
                float cpaY = projWorldY + projWorldVy * tcpa;
                float missDist = std::hypot(playerWorldX - cpaX, playerWorldY - cpaY);

                if (missDist < (playerRadius * 1.6f)) {
                    threatLevel = (tcpa < 0.12f) ? 3.0f : (tcpa < 0.25f ? 2.0f : 1.0f);
                    // Perpendicular evasion
                    float trajAngle = std::atan2(projWorldVy, projWorldVx);
                    float dodgeRad = trajAngle + 1.570796f; // +90 degrees
                    dodgeAngleDeg = std::fmod(dodgeRad * 57.29578f + 360.0f, 360.0f);
                    break;
                }
            }
        }
    }

    // Save previous frame for next differential step
    std::memcpy(pipeline->prevFrame.data(), pipeline->currentFrame.data(), pipeline->totalPixels);
    pipeline->hasPreviousFrame = true;

    // Serialize output to outThreatData:
    // [0] = threatLevel (0..3)
    // [1] = dodgeAngleDeg (0..360 or -1)
    // [2] = playerWorldX
    // [3] = playerWorldY
    // [4] = motionDx
    // [5] = motionDy
    // [6] = activeTrackCount
    jsize outLen = env->GetArrayLength(outThreatData);
    if (outLen >= 7) {
        jfloat buffer[7];
        buffer[0] = threatLevel;
        buffer[1] = dodgeAngleDeg;
        buffer[2] = playerWorldX;
        buffer[3] = playerWorldY;
        buffer[4] = static_cast<float>(pipeline->motionDx);
        buffer[5] = static_cast<float>(pipeline->motionDy);
        buffer[6] = static_cast<float>(pipeline->tracks.size());
        env->SetFloatArrayRegion(outThreatData, 0, 7, buffer);
    }

    return (threatLevel > 0.0f) ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeRenderaPipeline_nativeReset(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    if (!handle) return;
    auto* pipeline = reinterpret_cast<rendera::RenderaPipeline*>(handle);
    pipeline->reset();
}

JNIEXPORT void JNICALL
Java_com_example_vision_nativebridge_NativeRenderaPipeline_nativeDestroy(
    JNIEnv* env,
    jobject thiz,
    jlong handle
) {
    if (!handle) return;
    auto* pipeline = reinterpret_cast<rendera::RenderaPipeline*>(handle);
    delete pipeline;
}

} // extern "C"

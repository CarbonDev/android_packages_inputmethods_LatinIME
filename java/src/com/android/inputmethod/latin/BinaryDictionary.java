/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.inputmethod.latin;

import android.content.Context;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.inputmethod.keyboard.ProximityInfo;
import com.android.inputmethod.latin.SuggestedWords.SuggestedWordInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

/**
 * Implements a static, compacted, binary dictionary of standard words.
 */
public final class BinaryDictionary extends Dictionary {
    private static final String TAG = BinaryDictionary.class.getSimpleName();
    public static final String DICTIONARY_PACK_AUTHORITY =
            "com.android.inputmethod.latin.dictionarypack";

    /**
     * There is a difference between what java and native code can handle.
     * This value should only be used in BinaryDictionary.java
     * It is necessary to keep it at this value because some languages e.g. German have
     * really long words.
     */
    public static final int MAX_WORD_LENGTH = Constants.Dictionary.MAX_WORD_LENGTH;
    public static final int MAX_WORDS = 18;
    public static final int MAX_SPACES = 16;

    private static final int MAX_PREDICTIONS = 60;
    private static final int MAX_RESULTS = Math.max(MAX_PREDICTIONS, MAX_WORDS);

    private long mNativeDict;
    private final Locale mLocale;
    private final int[] mInputCodePoints = new int[MAX_WORD_LENGTH];
    private final int[] mOutputCodePoints = new int[MAX_WORD_LENGTH * MAX_RESULTS];
    private final int[] mSpaceIndices = new int[MAX_SPACES];
    private final int[] mOutputScores = new int[MAX_RESULTS];
    private final int[] mOutputTypes = new int[MAX_RESULTS];

    private final boolean mUseFullEditDistance;

    private final SparseArray<DicTraverseSession> mDicTraverseSessions =
            CollectionUtils.newSparseArray();

    // TODO: There should be a way to remove used DicTraverseSession objects from
    // {@code mDicTraverseSessions}.
    private DicTraverseSession getTraverseSession(final int traverseSessionId) {
        synchronized(mDicTraverseSessions) {
            DicTraverseSession traverseSession = mDicTraverseSessions.get(traverseSessionId);
            if (traverseSession == null) {
                traverseSession = mDicTraverseSessions.get(traverseSessionId);
                if (traverseSession == null) {
                    traverseSession = new DicTraverseSession(mLocale, mNativeDict);
                    mDicTraverseSessions.put(traverseSessionId, traverseSession);
                }
            }
            return traverseSession;
        }
    }

    /**
     * Constructor for the binary dictionary. This is supposed to be called from the
     * dictionary factory.
     * @param context the context to access the environment from.
     * @param filename the name of the file to read through native code.
     * @param offset the offset of the dictionary data within the file.
     * @param length the length of the binary data.
     * @param useFullEditDistance whether to use the full edit distance in suggestions
     * @param dictType the dictionary type, as a human-readable string
     */
    public BinaryDictionary(final Context context, final String filename, final long offset,
            final long length, final boolean useFullEditDistance, final Locale locale,
            final String dictType) {
        super(dictType);
        mLocale = locale;
        mUseFullEditDistance = useFullEditDistance;
        loadDictionary(filename, offset, length);
    }

    static {
        JniUtils.loadNativeLibrary();
    }

    private native long openNative(String sourceDir, long dictOffset, long dictSize,
            int maxWordLength, int maxWords, int maxPredictions);
    private native void closeNative(long dict);
    private native int getFrequencyNative(long dict, int[] word);
    private native boolean isValidBigramNative(long dict, int[] word1, int[] word2);
    private native int getSuggestionsNative(long dict, long proximityInfo, long traverseSession,
            int[] xCoordinates, int[] yCoordinates, int[] times, int[] pointerIds,
            int[] inputCodePoints, int codesSize, int commitPoint, boolean isGesture,
            int[] prevWordCodePointArray, boolean useFullEditDistance, int[] outputCodePoints,
            int[] outputScores, int[] outputIndices, int[] outputTypes);
    private static native float calcNormalizedScoreNative(int[] before, int[] after, int score);
    private static native int editDistanceNative(int[] before, int[] after);

    // TODO: Move native dict into session
    private final void loadDictionary(final String path, final long startOffset,
            final long length) {
        mNativeDict = openNative(path, startOffset, length, MAX_WORD_LENGTH, MAX_WORDS,
                MAX_PREDICTIONS);
    }

    @Override
    public ArrayList<SuggestedWordInfo> getSuggestions(final WordComposer composer,
            final String prevWord, final ProximityInfo proximityInfo) {
        return getSuggestionsWithSessionId(composer, prevWord, proximityInfo, 0);
    }

    @Override
    public ArrayList<SuggestedWordInfo> getSuggestionsWithSessionId(final WordComposer composer,
            final String prevWord, final ProximityInfo proximityInfo, int sessionId) {
        if (!isValidDictionary()) return null;

        Arrays.fill(mInputCodePoints, Constants.NOT_A_CODE);
        // TODO: toLowerCase in the native code
        final int[] prevWordCodePointArray = (null == prevWord)
                ? null : StringUtils.toCodePointArray(prevWord);
        final int composerSize = composer.size();

        final boolean isGesture = composer.isBatchMode();
        if (composerSize <= 1 || !isGesture) {
            if (composerSize > MAX_WORD_LENGTH - 1) return null;
            for (int i = 0; i < composerSize; i++) {
                mInputCodePoints[i] = composer.getCodeAt(i);
            }
        }

        final InputPointers ips = composer.getInputPointers();
        final int codesSize = isGesture ? ips.getPointerSize() : composerSize;
        // proximityInfo and/or prevWordForBigrams may not be null.
        final int tmpCount = getSuggestionsNative(mNativeDict,
                proximityInfo.getNativeProximityInfo(), getTraverseSession(sessionId).getSession(),
                ips.getXCoordinates(), ips.getYCoordinates(), ips.getTimes(), ips.getPointerIds(),
                mInputCodePoints, codesSize, 0 /* commitPoint */, isGesture, prevWordCodePointArray,
                mUseFullEditDistance, mOutputCodePoints, mOutputScores, mSpaceIndices,
                mOutputTypes);
        final int count = Math.min(tmpCount, MAX_PREDICTIONS);

        final ArrayList<SuggestedWordInfo> suggestions = CollectionUtils.newArrayList();
        for (int j = 0; j < count; ++j) {
            if (composerSize > 0 && mOutputScores[j] < 1) break;
            final int start = j * MAX_WORD_LENGTH;
            int len = 0;
            while (len < MAX_WORD_LENGTH && mOutputCodePoints[start + len] != 0) {
                ++len;
            }
            if (len > 0) {
                final int score = SuggestedWordInfo.KIND_WHITELIST == mOutputTypes[j]
                        ? SuggestedWordInfo.MAX_SCORE : mOutputScores[j];
                suggestions.add(new SuggestedWordInfo(new String(mOutputCodePoints, start, len),
                        score, mOutputTypes[j], mDictType));
            }
        }
        return suggestions;
    }

    public boolean isValidDictionary() {
        return mNativeDict != 0;
    }

    public static float calcNormalizedScore(final String before, final String after,
            final int score) {
        return calcNormalizedScoreNative(StringUtils.toCodePointArray(before),
                StringUtils.toCodePointArray(after), score);
    }

    public static int editDistance(final String before, final String after) {
        if (before == null || after == null) {
            throw new IllegalArgumentException();
        }
        return editDistanceNative(StringUtils.toCodePointArray(before),
                StringUtils.toCodePointArray(after));
    }

    @Override
    public boolean isValidWord(final String word) {
        return getFrequency(word) >= 0;
    }

    @Override
    public int getFrequency(final String word) {
        if (word == null) return -1;
        int[] codePoints = StringUtils.toCodePointArray(word);
        return getFrequencyNative(mNativeDict, codePoints);
    }

    // TODO: Add a batch process version (isValidBigramMultiple?) to avoid excessive numbers of jni
    // calls when checking for changes in an entire dictionary.
    public boolean isValidBigram(final String word1, final String word2) {
        if (TextUtils.isEmpty(word1) || TextUtils.isEmpty(word2)) return false;
        final int[] codePoints1 = StringUtils.toCodePointArray(word1);
        final int[] codePoints2 = StringUtils.toCodePointArray(word2);
        return isValidBigramNative(mNativeDict, codePoints1, codePoints2);
    }

    @Override
    public void close() {
        synchronized (mDicTraverseSessions) {
            final int sessionsSize = mDicTraverseSessions.size();
            for (int index = 0; index < sessionsSize; ++index) {
                final DicTraverseSession traverseSession = mDicTraverseSessions.valueAt(index);
                if (traverseSession != null) {
                    traverseSession.close();
                }
            }
        }
        closeInternal();
    }

    private synchronized void closeInternal() {
        if (mNativeDict != 0) {
            closeNative(mNativeDict);
            mNativeDict = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            closeInternal();
        } finally {
            super.finalize();
        }
    }
}

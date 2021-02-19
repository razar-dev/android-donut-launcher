/*
 * Copyright (C) 2008 The Android Open Source Project
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
package ussr.razar.android.dount.launcher

import android.view.View

/**
 * Interface for initiating a drag within a view or across multiple views.
 *
 */
open interface DragController {
    /**
     * Interface to receive notifications when a drag starts or stops
     */
    open interface DragListener {
        /**
         * A drag has begun
         *
         * @param v The view that is being dragged
         * @param source An object representing where the drag originated
         * @param info The data associated with the object that is being dragged
         * @param dragAction The drag action: either [DragController.DRAG_ACTION_MOVE]
         * or [DragController.DRAG_ACTION_COPY]
         */
        fun onDragStart(v: View?, source: DragSource?, info: Any?, dragAction: Int)

        /**
         * The drag has eneded
         */
        fun onDragEnd()
    }

    /**
     * Starts a drag
     *
     * @param v The view that is being dragged
     * @param source An object representing where the drag originated
     * @param info The data associated with the object that is being dragged
     * @param dragAction The drag action: either [.DRAG_ACTION_MOVE] or
     * [.DRAG_ACTION_COPY]
     */
    fun startDrag(v: View?, source: DragSource?, info: Any?, dragAction: Int)

    companion object {
        /**
         * Indicates the drag is a move.
         */
        const val DRAG_ACTION_MOVE: Int = 0

        /**
         * Indicates the drag is a copy.
         */
        const val DRAG_ACTION_COPY: Int = 1
    }
}
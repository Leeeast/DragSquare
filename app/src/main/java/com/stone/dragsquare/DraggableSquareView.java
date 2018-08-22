package com.stone.dragsquare;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Message;
import android.support.v4.view.GestureDetectorCompat;
import android.support.v4.widget.ViewDragHelper;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * 正方形的拖拽面板
 * Created by xmuSistone on 2016/5/23.
 */
public class DraggableSquareView extends ViewGroup {
    // ACTION_DOWN按下后超过这个时间，就直接touch拦截，不会调用底层view的onClick事件
    private static final int INTERCEPT_TIME_SLOP = 200;
    private final int[] allStatus = {DraggableItemView.STATUS_LEFT_TOP, DraggableItemView.STATUS_RIGHT_TOP,
            DraggableItemView.STATUS_RIGHT_MIDDLE, DraggableItemView.STATUS_RIGHT_BOTTOM,
            DraggableItemView.STATUS_MIDDLE_BOTTOM, DraggableItemView.STATUS_LEFT_BOTTOM};

    private int mTouchSlop = 5; // 判定为滑动的阈值，单位是像素
    private final ViewDragHelper mDragHelper;
    private GestureDetectorCompat moveDetector;

    private List<Point> originViewPositionList = new ArrayList<>(); // 保存最初状态时每个itemView的坐标位置
    private List<Rect> originViewRectList = new ArrayList<>(); // 保存最初状态时每个itemView的坐标位置
    private DraggableItemView draggingView; // 正在拖拽的view

    private long downTime = 0; // 按下的时间
    private int downX, downY;  // 按下时的坐标位置
    private Thread moveAnchorThread; // 按下的时候，itemView的重心移动，此为对应线程
    private Handler anchorHandler; // itemView需要移动重心，此为对应的Handler

    public int mBigRadius;
    public int mSmallRadius;
    private int mLayoutRadius;

    public DraggableSquareView(Context context) {
        this(context, null);
    }

    public DraggableSquareView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DraggableSquareView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mDragHelper = ViewDragHelper
                .create(this, 10f, new DragHelperCallback());
        moveDetector = new GestureDetectorCompat(context,
                new MoveDetector());
        moveDetector.setIsLongpressEnabled(false); // 不能处理长按事件，否则违背最初设计的初衷

        // 滑动的距离阈值由系统提供
        ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mTouchSlop = configuration.getScaledTouchSlop();

        anchorHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                if (draggingView != null) {
                    // 开始移动重心的动画
                    draggingView.startAnchorAnimation();
                }
            }
        };
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        int len = allStatus.length;
        for (int i = 0; i < len; i++) {
            // 渲染结束之后，朝viewGroup中添加子View
            DraggableItemView itemView = new DraggableItemView(getContext());
            itemView.setStatus(allStatus[i]);
            itemView.setParentView(this);
            originViewPositionList.add(new Point()); //  原始位置点，由此初始化，一定与子View的status绑定
            originViewRectList.add(new Rect());
            addView(itemView);
        }
        postDelayed(new Runnable() {
            @Override
            public void run() {
                requestLayout();
            }
        },50);
    }

    public Point getOriginViewPos(int status) {
        return originViewPositionList.get(status);
    }

    public Rect getOriginViewRect(int status) {
        return originViewRectList.get(status);
    }

    /**
     * 给imageView添加图片
     */
    public void fillItemImage(int imageStatus, String imagePath, boolean isModify) {
        // 1. 如果是修改图片，直接填充就好
        if (isModify) {
            DraggableItemView itemView = getItemViewByStatus(imageStatus);
            itemView.fillImageView(imagePath);
            return;
        }

        // 2. 新增图片
        for (int i = 0; i < allStatus.length; i++) {
            DraggableItemView itemView = getItemViewByStatus(i);
            if (!itemView.isDraggable()) {
                itemView.fillImageView(imagePath);
                break;
            }
        }
    }

    /**
     * 删除某一个ImageView时，该imageView变成空的，需要移动到队尾
     */
    public void onDedeleteImage(DraggableItemView deleteView) {
        int status = deleteView.getStatus();
        int lastDraggableViewStatus = -1;
        // 顺次将可拖拽的view往前移
        for (int i = status + 1; i < allStatus.length; i++) {
            DraggableItemView itemView = getItemViewByStatus(i);
            if (itemView.isDraggable()) {
                // 可拖拽的view往前移
                lastDraggableViewStatus = i;
                switchPosition(i, i - 1);
            } else {
                break;
            }
        }
        if (lastDraggableViewStatus > 0) {
            // 被delete的view移动到队尾
            deleteView.switchPosition(lastDraggableViewStatus);
        }
    }

    /**
     * 这是viewdraghelper拖拽效果的主要逻辑
     */
    private class DragHelperCallback extends ViewDragHelper.Callback {

        @Override
        public void onViewPositionChanged(View changedView, int left, int top, int dx, int dy) {
            // draggingView拖动的时候，如果与其它子view交换位置，其他子view位置改变，也会进入这个回调
            // 所以此处加了一层判断，剔除不关心的回调，以优化性能
            if (changedView == draggingView) {
                DraggableItemView changedItemView = (DraggableItemView) changedView;
                switchPositionIfNeeded(changedItemView);
            }
        }

        @Override
        public boolean tryCaptureView(View child, int pointerId) {
            // 按下的时候，缩放到最小的级别
            draggingView = (DraggableItemView) child;
            return draggingView.isDraggable();
        }

        @Override
        public void onViewReleased(View releasedChild, float xvel, float yvel) {
            DraggableItemView itemView = (DraggableItemView) releasedChild;
            itemView.onDragRelease();
        }

        @Override
        public int clampViewPositionHorizontal(View child, int left, int dx) {
            DraggableItemView itemView = (DraggableItemView) child;
            return itemView.computeDraggingX(dx);
        }

        @Override
        public int clampViewPositionVertical(View child, int top, int dy) {
            DraggableItemView itemView = (DraggableItemView) child;
            return itemView.computeDraggingY(dy);
        }
    }

    /**
     * 根据draggingView的位置，看看是否需要与其它itemView互换位置
     */
    private void switchPositionIfNeeded(DraggableItemView draggingView) {
        int centerX = draggingView.getLeft() + draggingView.getWidth() / 2;
        int centerY = draggingView.getTop() + draggingView.getWidth() / 2;

        int fromStatus = -1, toStatus = draggingView.getStatus();
        for (int i = 0; i < getChildCount(); i++) {
            if (draggingView.getStatus() != i) {
                DraggableItemView changeItemView = getItemViewByStatus(i);
                if (changeItemView.getLeft() < centerX
                        && centerX < changeItemView.getRight()
                        && centerY > changeItemView.getTop()
                        && centerY < changeItemView.getBottom()) {
                    fromStatus = changeItemView.getStatus();
                }
            }
        }
        if (fromStatus >= 0 && fromStatus != toStatus) {
            if (switchPosition(fromStatus, toStatus)) {
                draggingView.setStatus(fromStatus);
            }
        }
    }

    /**
     * 调换位置
     */
    private boolean switchPosition(int fromStatus, int toStatus) {
        DraggableItemView itemView = getItemViewByStatus(fromStatus);
        if (itemView.isDraggable()) {
            itemView.switchPosition(toStatus);
            return true;
        }
        return false;
    }

    /**
     * 根据status获取itemView
     */
    private DraggableItemView getItemViewByStatus(int status) {
        int num = getChildCount();
        for (int i = 0; i < num; i++) {
            DraggableItemView itemView = (DraggableItemView) getChildAt(i);
            if (itemView.getStatus() == status) {
                return itemView;
            }
        }
        return null;
    }

    class MoveDetector extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx,
                                float dy) {
            // 拖动了，touch不往下传递
            return Math.abs(dy) + Math.abs(dx) > mTouchSlop;
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int itemLeft = 0;
        int itemTop = 0;
        int itemRight = 0;
        int itemBottom = 0;
        mLayoutRadius = dip2px(getContext(), 100);
        mBigRadius = dip2px(getContext(), 40);
        mSmallRadius = dip2px(getContext(), 23);
        mSmallRadius = mBigRadius;
        Point centerP = new Point(getMeasuredWidth() / 2, mBigRadius);
        Point leftTopP = new Point(centerP.x - mLayoutRadius, centerP.y);
        Point leftBottomP = new Point((int) (centerP.x - mLayoutRadius * 0.707), (int) (centerP.y + mLayoutRadius * 0.707));
        Point bottomP = new Point(centerP.x, centerP.y + mLayoutRadius);
        Point rightBottomP = new Point((int) (centerP.x + mLayoutRadius * 0.707), (int) (centerP.y + mLayoutRadius * 0.707));
        Point rightTopP = new Point(centerP.x + mLayoutRadius, centerP.y);

        int num = getChildCount();
        for (int i = 0; i < num; i++) {
            DraggableItemView itemView = (DraggableItemView) getChildAt(i);
            itemView.setScaleRate(45f/80f);
            switch (itemView.getStatus()) {
                case DraggableItemView.STATUS_LEFT_TOP:
                    itemLeft = centerP.x - mBigRadius;
                    itemRight = centerP.x + mBigRadius;
                    itemTop = centerP.y - mBigRadius;
                    itemBottom = centerP.y + mBigRadius;
                    break;
                case DraggableItemView.STATUS_RIGHT_TOP:
                    itemLeft = rightTopP.x - mSmallRadius;
                    itemRight = rightTopP.x + mSmallRadius;
                    itemTop = rightTopP.y - mSmallRadius;
                    itemBottom = rightTopP.y + mSmallRadius;
                    break;
                case DraggableItemView.STATUS_RIGHT_MIDDLE:
                    itemLeft = rightBottomP.x - mSmallRadius;
                    itemRight = rightBottomP.x + mSmallRadius;
                    itemTop = rightBottomP.y - mSmallRadius;
                    itemBottom = rightBottomP.y + mSmallRadius;
                    break;
                case DraggableItemView.STATUS_RIGHT_BOTTOM:
                    itemLeft = bottomP.x - mSmallRadius;
                    itemRight = bottomP.x + mSmallRadius;
                    itemTop = bottomP.y - mSmallRadius;
                    itemBottom = bottomP.y + mSmallRadius;
                    break;
                case DraggableItemView.STATUS_MIDDLE_BOTTOM:
                    itemLeft = leftBottomP.x - mSmallRadius;
                    itemRight = leftBottomP.x + mSmallRadius;
                    itemTop = leftBottomP.y - mSmallRadius;
                    itemBottom = leftBottomP.y + mSmallRadius;
                    break;
                case DraggableItemView.STATUS_LEFT_BOTTOM:
                    itemLeft = leftTopP.x - mSmallRadius;
                    itemRight = leftTopP.x + mSmallRadius;
                    itemTop = leftTopP.y - mSmallRadius;
                    itemBottom = leftTopP.y + mSmallRadius;
                    break;
            }
            int sideLength = itemView.getStatus() == DraggableItemView.STATUS_LEFT_TOP ? mBigRadius * 2 : mSmallRadius * 2;
            ViewGroup.LayoutParams lp = itemView.getLayoutParams();
            lp.width = sideLength;
            lp.height = sideLength;
            itemView.setLayoutParams(lp);

            Point itemPoint = originViewPositionList.get(itemView.getStatus());
            itemPoint.x = itemLeft;
            itemPoint.y = itemTop;

            Rect rect = originViewRectList.get(itemView.getStatus());
            rect.left = itemLeft;
            rect.right = itemRight;
            rect.top = itemTop;
            rect.bottom = itemBottom;

            itemView.layout(itemLeft, itemTop, itemRight, itemBottom);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        measureChildren(widthMeasureSpec, heightMeasureSpec);
        int maxWidth = MeasureSpec.getSize(widthMeasureSpec);
        int width = resolveSizeAndState(maxWidth, widthMeasureSpec, 0);
        setMeasuredDimension(width, mLayoutRadius + mBigRadius + mSmallRadius);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            // 手指按下的时候，需要把某些view bringToFront，否则的话，tryCapture将不按预期工作
            downX = (int) ev.getX();
            downY = (int) ev.getY();
            downTime = System.currentTimeMillis();
            bringToFrontWhenTouchDown(downX, downY);
        } else if (ev.getAction() == MotionEvent.ACTION_UP) {
            if (draggingView != null) {
                draggingView.onDragRelease();
            }
            draggingView = null;
            if (null != moveAnchorThread) {
                moveAnchorThread.interrupt();
                moveAnchorThread = null;
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 按下时根据触点的位置，将某个view bring到前台
     */
    private void bringToFrontWhenTouchDown(final int downX, final int downY) {
        int statusIndex = getStatusByDownPoint(downX, downY);

        if (statusIndex < 0) return;

        final DraggableItemView itemView = getItemViewByStatus(statusIndex);
        if (indexOfChild(itemView) != getChildCount() - 1) {
            bringChildToFront(itemView);
        }
        if (!itemView.isDraggable()) {
            getParent().requestDisallowInterceptTouchEvent(false);
            return;
        } else {
            getParent().requestDisallowInterceptTouchEvent(true);
        }

        itemView.saveAnchorInfo(downX, downY);
        moveAnchorThread = new Thread() {
            @Override
            public void run() {
                try {
                    sleep(INTERCEPT_TIME_SLOP);
                } catch (InterruptedException e) {
                }

                Message msg = anchorHandler.obtainMessage();
                msg.sendToTarget();
            }
        };
        moveAnchorThread.start();
    }

    private int getStatusByDownPoint(int downX, int downY) {
        for (int i = 0; i < getChildCount(); i++) {
            DraggableItemView itemView = getItemViewByStatus(i);
            if (itemView.getLeft() < downX
                    && downX < itemView.getRight()
                    && downY > itemView.getTop()
                    && downY < itemView.getBottom()) {
                return itemView.getStatus();
            }
        }
        return -1;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (downTime > 0 && System.currentTimeMillis() - downTime > INTERCEPT_TIME_SLOP) {
            return true;
        }
        boolean shouldIntercept = mDragHelper.shouldInterceptTouchEvent(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mDragHelper.processTouchEvent(ev);
        }

        boolean moveFlag = moveDetector.onTouchEvent(ev);
        if (moveFlag) {
            if (null != moveAnchorThread) {
                moveAnchorThread.interrupt();
                moveAnchorThread = null;
            }

            if (null != draggingView && draggingView.isDraggable()) {
                draggingView.startAnchorAnimation();
            }
        }
        return shouldIntercept && moveFlag;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        try {
            // 该行代码可能会抛异常，正式发布时请将这行代码加上try catch
            mDragHelper.processTouchEvent(e);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return true;
    }

    public int dip2px(Context context, float dipValue) {
        final float scale = context.getResources().getDisplayMetrics().density;
        return (int) (dipValue * scale + 1.5f);
    }
}
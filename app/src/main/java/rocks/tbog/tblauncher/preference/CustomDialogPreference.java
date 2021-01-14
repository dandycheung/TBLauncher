package rocks.tbog.tblauncher.preference;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceViewHolder;

import rocks.tbog.tblauncher.R;
import rocks.tbog.tblauncher.utils.PrefCache;
import rocks.tbog.tblauncher.utils.UIColors;
import rocks.tbog.tblauncher.utils.UISizes;

public class CustomDialogPreference extends androidx.preference.DialogPreference {

    private Object mValue = null;

    public CustomDialogPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public CustomDialogPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CustomDialogPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomDialogPreference(Context context) {
        super(context);
    }

    public Object getValue() {
        return mValue;
    }

    public void setValue(Object value) {
        mValue = value;
    }

    public void persistValue() {
        Object value = getValue();
        if (value instanceof String)
            persistString((String) value);
        else if (value instanceof Integer)
            persistInt((Integer) value);
    }

    public boolean persistValueIfAllowed() {
        if (callChangeListener(getValue())) {
            persistValue();
            return true;
        }
        return false;
    }

    public boolean persistValueIfAllowed(Object value) {
        if (callChangeListener(value)) {
            setValue(value);
            persistValue();
            return true;
        }
        return false;
    }

    @Override
    protected void onSetInitialValue(@Nullable Object defaultValue) {
        if (getValue() == null)
            setValue(defaultValue);
        persistValue();
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        try {
            return a.getInteger(index, 0);
        } catch (UnsupportedOperationException e) {
            return a.getString(index);
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        Object value = getSharedPreferences().getAll().get(getKey());

        {
            View view = holder.findViewById(R.id.prefColorPreview);
            if (view instanceof ImageView) {
                int color = 0xFFffffff;
                if (value instanceof Integer)
                    color = (int) value | 0xFF000000;

                float luminance = UIColors.luminance(color);
                int borderColor = UIColors.modulateColorLightness(color, 2.f * (1.f - luminance));

                Context ctx = getContext();
                float radius = ctx.getResources().getDimension(R.dimen.mm2d_cc_sample_radius);
                int border = UISizes.dp2px(ctx, 1);
                GradientDrawable drawable = new GradientDrawable();
                drawable.setCornerRadius(radius);
                drawable.setStroke(border, borderColor);
                drawable.setColor(color);
                ((ImageView) view).setImageDrawable(drawable);
                view.setPadding(border, border, border, border);
                //((ImageView) view).setColorFilter(color, PorterDuff.Mode.MULTIPLY);
            }
        }
        {
            View view = holder.findViewById(R.id.prefAlphaPreview);
            if (view instanceof TextView) {
                String text = null;
                if (value instanceof Integer)
                    text = ((Integer) value) * 100 / 255 + "%";
                ((TextView) view).setText(text);
            }
        }
        {
            View view = holder.findViewById(R.id.prefSizePreview);
            if (view instanceof TextView) {
                int size = -1;
                if ("result-search-cap".equals(getKey()))
                    size = PrefCache.getResultSearcherCap(getContext());
                else if (value instanceof Integer)
                    size = (int) value;
                ((TextView) view).setText(view.getResources().getString(R.string.size, size));
            }
        }
    }
}

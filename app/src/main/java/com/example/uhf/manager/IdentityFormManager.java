package com.example.uhf.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextWatcher;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public class IdentityFormManager {

    private static final String OFFLINE_PREFS = "identity_studio_cache";
    private static final String TAG = "IdentityFormManager";

    private final Context context;
    private final SharedPreferences offlineCache;

    public interface FormData {
        String getEpc();
        String getTitle();
        String getGrossWeight();
        boolean isHasStones();
        String getStoneType();
        String getStoneWeight();
        String getStoneCount();
        String getStoneValue();
        void setEpc(String value);
        void setTitle(String value);
        void setGrossWeight(String value);
        void setStoneType(String value);
        void setStoneWeight(String value);
        void setStoneCount(String value);
        void setStoneValue(String value);
        void setHasStones(boolean value);
    }

    public interface FormDataListener {
        void onFormDataChanged();
    }

    public IdentityFormManager(Context context) {
        this.context = context;
        this.offlineCache = context.getSharedPreferences(OFFLINE_PREFS, Context.MODE_PRIVATE);
    }

    public void saveDraft(FormData formData, List<String> photoPaths) {
        try {
            JSONObject draft = new JSONObject();
            draft.put("epc", formData.getEpc());
            draft.put("title", formData.getTitle());
            draft.put("gross_weight", formData.getGrossWeight());
            draft.put("has_stones", formData.isHasStones());
            
            if (formData.isHasStones()) {
                draft.put("stone_type", formData.getStoneType());
                draft.put("stone_weight", formData.getStoneWeight());
                draft.put("stone_count", formData.getStoneCount());
                draft.put("stone_value", formData.getStoneValue());
            }
            
            if (!photoPaths.isEmpty()) {
                JSONArray photosArray = new JSONArray();
                for (String path : photoPaths) {
                    photosArray.put(path);
                }
                draft.put("photo_paths", photosArray);
            }
            
            offlineCache.edit().putString("current_draft", draft.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "saveDraft error: " + e.getMessage());
        }
    }

    public boolean restoreDraft(FormData formData, List<String> photoPaths) {
        try {
            String draftJson = offlineCache.getString("current_draft", null);
            if (draftJson == null) return false;

            JSONObject draft = new JSONObject(draftJson);
            
            formData.setEpc(draft.optString("epc"));
            formData.setTitle(draft.optString("title"));
            formData.setGrossWeight(draft.optString("gross_weight"));
            
            boolean hasStones = draft.optBoolean("has_stones");
            formData.setHasStones(hasStones);
            
            if (hasStones) {
                formData.setStoneType(draft.optString("stone_type"));
                formData.setStoneWeight(draft.optString("stone_weight"));
                formData.setStoneCount(draft.optString("stone_count"));
                formData.setStoneValue(draft.optString("stone_value"));
            }
            
            if (draft.has("photo_paths")) {
                JSONArray photosArray = draft.optJSONArray("photo_paths");
                if (photosArray != null) {
                    photoPaths.clear();
                    for (int i = 0; i < photosArray.length(); i++) {
                        String path = photosArray.optString(i);
                        if (path != null && !path.isEmpty() && new File(path).exists()) {
                            photoPaths.add(path);
                        }
                    }
                }
            }

            offlineCache.edit().remove("current_draft").apply();
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "restoreDraft error: " + e.getMessage());
            return false;
        }
    }

    public void clearDraft() {
        offlineCache.edit().remove("current_draft").apply();
    }
}

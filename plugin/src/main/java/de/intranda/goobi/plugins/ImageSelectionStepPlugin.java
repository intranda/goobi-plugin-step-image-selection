package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;

import org.apache.commons.collections4.map.ListOrderedMap;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.lang.StringUtils;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.StorageProviderInterface;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.metadaten.Image;
import de.sub.goobi.persistence.managers.PropertyManager;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class ImageSelectionStepPlugin implements IStepPluginVersion2 {

    @Getter
    private String title = "intranda_step_image_selection";
    @Getter
    private Step step;

    @Getter
    private boolean allowTaskFinishButtons;
    private String returnPath;

    private String folderName;

    private Process process;

    private static final String PROPERTY_TITLE = "plugin_intranda_step_image_selection";

    // index of the very last loaded image
    private int currentIndex = 0;

    // list of all images
    private List<Image> images = new ArrayList<>();

    // list of images that shall be loaded in the beginning
    private List<Image> imagesFirstLoad = new ArrayList<>();

    // list of images that are already loaded
    @Getter
    private List<Image> imagesToShow = new ArrayList<>();

    // map containing all selected images
    private ListOrderedMap<Integer, Image> selectedImageMap = new ListOrderedMap<>();

    @Getter
    private int thumbnailSize = 200;
    // default number of images to load in the beginning
    private int defaultNumberToLoad;
    // default number of additional images to load when scrolled to the bottom
    private int defaultNumberToAdd;
    // maximum number of images allowed to be selected
    private int maxSelectionAllowed;
    // minimum number of images allowed to be selected before saving property
    private int minSelectionAllowed;

    private boolean moreImagesAvailable;

    @Getter
    @Setter
    private int lastYOffset = 0; // used to control the automatic loading of more images when scrolled to be bottom

    @Getter
    @Setter
    private int draggedIndex = -1; // index of the dragged image among its source list (either loaded or selected), -1 if nothing is dragged

    @Getter
    @Setter
    private int indexToPut = -1; // the targeted index among the list of all selected for the dragged image to drop

    private Processproperty property = null;

    private static StorageProviderInterface storageProvider = StorageProvider.getInstance();

    /**
     * initialize important fields
     */
    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;

        // read parameters from correct block in configuration file
        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
        folderName = myconfig.getString("folder", "master");

        defaultNumberToLoad = myconfig.getInt("defaultNumberToLoad", 20);
        defaultNumberToAdd = myconfig.getInt("defaultNumberToAdd", 10);

        maxSelectionAllowed = myconfig.getInt("max", 5);
        minSelectionAllowed = myconfig.getInt("min", 1);

        if (maxSelectionAllowed < minSelectionAllowed) {
            log.debug("The configured max is less than the configured min, hence no selection will be allowed.");
            maxSelectionAllowed = 0;
            minSelectionAllowed = 0;
        }

        allowTaskFinishButtons = myconfig.getBoolean("allowTaskFinishButtons", false);
        log.info("ImageSelection step plugin initialized");
        process = this.step.getProzess();
        try {
            // get folderPath
            Path folderPath = initializeFolderPath(process, folderName);
            log.debug("folder path = " + folderPath);

            // initialize images
            initializeImages(folderPath);

        } catch (IOException | SwapException | DAOException e) {
            log.error("Errors happened in the initialization phase.");
            log.error(e.getMessage());
        }

    }

    /**
     * get the full path to the image folder
     * 
     * @param process Goobi process
     * @param folderName configured folder name
     * @return the full path to the image folder if it exists, null otherwise
     * @throws IOException
     * @throws SwapException
     * @throws DAOException
     */
    private Path initializeFolderPath(Process process, String folderName) throws IOException, SwapException, DAOException {
        String folder = process.getConfiguredImageFolder(folderName);
        if (StringUtils.isBlank(folder)) {
            log.debug("The folder configured as '" + folderName + "' does not exist yet.");
            return null;
        }
        return Path.of(folder);
    }

    /**
     * initialize the field images
     * 
     * @param folderPath full path to the image folder
     * @throws IOException
     * @throws SwapException
     * @throws DAOException
     */
    private void initializeImages(Path folderPath) throws IOException, SwapException, DAOException {
        if (folderPath == null) {
            log.debug("There are no images available for NULL!");
            return;
        }
        List<Path> imagePaths = storageProvider.listFiles(folderPath.toString());
        int order = 0;
        String imageFolderName = folderPath.getFileName().toString();
        Integer thumbnailSize = null;
        for (Path imagePath : imagePaths) {
            String fileName = imagePath.getFileName().toString();
            Image image = new Image(process, imageFolderName, fileName, order++, thumbnailSize);
            images.add(image);
        }
        int topIndex = Math.min(defaultNumberToLoad, images.size());
        imagesFirstLoad = images.subList(0, topIndex);
    }

    /**
     * load images in the beginning
     */
    public void loadImages() {
        loadFirstImages();
        readSelectedFromJson();
        showSelectedImages();
    }

    /**
     * load the heading images
     */
    public void loadFirstImages() {
        int topIndex = imagesFirstLoad.size();
        log.debug("The first " + topIndex + " imges in " + folderName + " will be shown:");
        imagesToShow = new ArrayList<>(imagesFirstLoad);
        currentIndex = topIndex;
        showImages(imagesToShow);
    }

    /**
     * read the information of selected Images from Json
     */
    private void readSelectedFromJson() {
        selectedImageMap = new ListOrderedMap<>();
        setUpProcesspropertyToSave(process.getId(), PROPERTY_TITLE);

        String values = property.getWert();
        if (StringUtils.isBlank(values) || values.length() <= 2) {
            // the property is empty
            return;
        }

        values = values.substring(1, values.length() - 1);
        String[] valuesList = values.split(",");
        int validNumberOfValues = valuesList.length;
        if (validNumberOfValues > maxSelectionAllowed) {
            log.debug("The saved property contains more selected items than it is allowed. Only the first " + maxSelectionAllowed
                    + " will be taken into account.");
            validNumberOfValues = maxSelectionAllowed;
        }
        String[] names = new String[validNumberOfValues];

        for (int i = 0; i < validNumberOfValues; ++i) {
            String value = valuesList[i];
            String[] valueParts = value.split(":");
            names[i] = valueParts[0].replace("\"", "");
        }
        initializeSelectedImageMap(names);
    }

    /**
     * initialize the field selectedImageMap
     * 
     * @param names names of Images that shall be stored into this map
     */
    private void initializeSelectedImageMap(String[] names) {
        HashMap<String, Integer> nameToIndexMap = new HashMap<>();
        for (String name : names) {
            nameToIndexMap.put(name, -1);
        }
        int unfound = names.length;
        for (int i = 0; i < images.size() && unfound > 0; ++i) {
            Image image = images.get(i);
            String imageName = image.getImageName();
            if (nameToIndexMap.containsKey(imageName)) {
                nameToIndexMap.put(imageName, i);
                --unfound;
            }
        }
        // initialize the selectedImageMap
        for (String name : names) {
            int index = nameToIndexMap.get(name);
            // check if there is still any -1 in the value list
            if (index < 0) {
                log.debug("The image " + name + " was not found. It is probably renamed or moved.");
                continue;
            }
            selectedImageMap.put(index, images.get(index));
        }
    }

    /**
     * load more images
     */
    public void loadMoreImages() {
        int topIndex = Math.min(currentIndex + defaultNumberToAdd, images.size());

        if (currentIndex == topIndex) {
            log.debug("All images are already shown.");
        }
        List<Image> imagesAdded = images.subList(currentIndex, topIndex);

        for (Image image : imagesAdded) {
            imagesToShow.add(image);
        }

        currentIndex = topIndex;
        showImages(imagesAdded);
    }

    /**
     * save the current choice as a process property
     * 
     * @return true if the property is successfully saved, false otherwise
     */
    public boolean saveAsProperty() {
        if (selectedImageMap.size() < minSelectionAllowed) {
            log.debug("Cannot save property. At least " + minSelectionAllowed + " should be selected.");
            return false;
        }
        setUpProcesspropertyToSave(process.getId(), PROPERTY_TITLE);
        String jsonOfSelected = createJsonOfSelectedImages();
        property.setWert(jsonOfSelected);
        log.debug(jsonOfSelected);
        PropertyManager.saveProcessProperty(property);

        return true;
    }

    /**
     * find the process property, or create one if none is found
     * 
     * @param processId id of the process
     * @param title title of the property
     */
    private void setUpProcesspropertyToSave(int processId, String title) {
        if (property != null) {
            // already initialized
            return;
        }
        List<Processproperty> props = PropertyManager.getProcessPropertiesForProcess(processId);
        for (Processproperty p : props) {
            if (title.equals(p.getTitel())) {
                property = p;
                return;
            }
        }
        // no such property exists yet, create a new one
        property = new Processproperty();
        property.setProcessId(processId);
        property.setTitel(title);
    }

    /**
     * create the Json string used as value for the process property
     * 
     * @return the Json string with keys being the names of selected images, and values being their urls
     */
    private String createJsonOfSelectedImages() {
        StringBuilder sb = new StringBuilder("{");
        Collection<Image> selectedImages = selectedImageMap.values();
        for (Image image : selectedImages) {
            sb.append("\"");
            sb.append(image.getImageName());
            sb.append("\":\"");
            sb.append(image.getTooltip());
            sb.append("\",");
        }
        // delete the last comma
        if (sb.length() > 1) {
            sb.deleteCharAt(sb.length() - 1);
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * print information of all shown images to the log
     * 
     * @param newImagesToShow List of Images that were not shown yet
     */
    private void showImages(List<Image> newImagesToShow) {
        log.debug("The number of new images to show is " + newImagesToShow.size());
        for (Image image : newImagesToShow) {
            log.debug(image.getImageName());
        }
        log.debug("The number of all images shown is " + currentIndex);
    }

    /**
     * print information of all selected images to the log
     */
    private void showSelectedImages() {
        Collection<Image> selectedImages = selectedImageMap.values();
        for (Image image : selectedImages) {
            log.debug(image.getImageName());
        }
        log.debug("The number of selected images is " + selectedImages.size());
    }

    /**
     * get a list of selected images
     * 
     * @return a list of selected Images
     */
    public Collection<Image> getImagesSelected() {
        return selectedImageMap.values();
    }

    /**
     * select the image with index selectedIndex
     */
    public void selectImage() {
        log.debug("draggedIndex = " + draggedIndex);
        if (draggedIndex < 0) {
            log.error("a negative draggedIndex is not valid");
            return;
        }
        boolean appendToEnd = indexToPut < 0;
        selectImage(draggedIndex, appendToEnd);
    }

    /**
     * select an image
     * 
     * @param name name of the image that is selected
     * @param startIndex a probably true index of the image among all images, search will start there for efficiency
     */
    private void selectImage(int startIndex, boolean appendToEnd) {
        if (selectedImageMap.size() == maxSelectionAllowed) {
            log.debug("Cannot select more since the maximum number allowed " + maxSelectionAllowed + " is already reached.");
            return;
        }
        if (appendToEnd) {
            indexToPut = selectedImageMap.size();
        }
        Image image = images.get(startIndex);
        if (!selectedImageMap.containsValue(image)) {
            selectedImageMap.put(indexToPut, startIndex, image);
            log.debug("new image selected: " + image.getImageName());
        }
    }

    /**
     * deselect the image with order deselectedIndex
     */
    public void deselectImage() {
        log.debug("deselect image with index = " + draggedIndex);
        if (draggedIndex < 0) {
            log.error("a negative index is not valid");
            return;
        }
        deselectImage(draggedIndex);
    }

    /**
     * deselect an image
     * 
     * @param order the index of the image among all selected images
     */
    private void deselectImage(int order) {
        // ListOrderedMap provides two kinds of remove:
        // one accepts an int as index and removes the element at this specified index
        // the other one accepts an Object as key and removes its mapped value accordingly
        Image deselected = selectedImageMap.remove(order);
        log.debug("Image deselected: " + deselected.getImageName());
        showSelectedImages();
    }

    /**
     * used to reorder among selected images via drag & drop
     */
    public void reorderSelected() {
        log.debug("indexToPut = " + indexToPut);
        if (indexToPut == draggedIndex || indexToPut < 0) {
            log.debug("no need to reorder");
            return;
        }
        // if we want to move an image downwards, then we need to reduce indexToPut by one 
        // this is due to the way we get our value via the ListOrderedMap::remove method
        if (draggedIndex < indexToPut) {
            --indexToPut;
        }
        // the selected image of selectedIndex should be moved to the place indexToPut
        // selectedIndex is hereby the order of this image among all selected
        Integer key = selectedImageMap.get(draggedIndex);
        Image value = selectedImageMap.remove(draggedIndex);
        selectedImageMap.put(indexToPut, key, value);
        showSelectedImages();
    }

    /**
     * switch positions of the selected image and its nearest neighbor upwards, and if the selected image is the top most one, then move it to the
     * bottom position
     * 
     * @param order the index of the image among all selected images
     */
    public void moveUpwards(int order) {
        log.debug("moving the selected image at position {} upwards", order);
        Integer key = selectedImageMap.get(order);
        Image value = selectedImageMap.remove(order);
        int targetedIndex = order > 0 ? order - 1 : selectedImageMap.size();
        selectedImageMap.put(targetedIndex, key, value);
    }

    /**
     * switch positions of the selected image and its nearest neighbor downwards, and if the selected image is the bottom most one, then move it to
     * the top position
     * 
     * @param order the index of the image among all selected images
     */
    public void moveDownwards(int order) {
        log.debug("moving the selected image at position {} downwards", order);
        Integer key = selectedImageMap.get(order);
        Image value = selectedImageMap.remove(order);
        int targetedIndex = order > selectedImageMap.size() - 1 ? 0 : order + 1;
        selectedImageMap.put(targetedIndex, key, value);
    }

    /**
     * get the number of all images, used to check if it is able to load more
     * 
     * @return the number of all images
     */
    public int getNumberOfImages() {
        return images.size();
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.FULL;
    }

    @Override
    public String getPagePath() {
        return "/uii/plugin_step_image_selection.xhtml";
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public PluginReturnValue run() {
        boolean successful = true;
        // your logic goes here

        // start loading
        loadImages();

        log.info("ImageSelection step plugin executed");
        if (!successful) {
            return PluginReturnValue.ERROR;
        }
        return PluginReturnValue.FINISH;
    }
}

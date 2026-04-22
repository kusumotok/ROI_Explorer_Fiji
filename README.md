# ROI Explorer for Fiji

ROI Explorer is a Fiji/ImageJ plugin for browsing, editing, measuring, and reorganizing ROI files as a folder tree.

The plugin presents ROI folders and ZIP archives in a TreeTable UI, can bind to an open image, and supports ROI-oriented workflows that are awkward in the default ROI Manager.

Menu location in Fiji:

- `Plugins > ROI Explorer`

## Main features

- Browse ROI folders, nested folders, and ROI ZIP archives in one TreeTable view
- Drag and drop ROI files and folders to reorganize the tree
- Bind the view to an open image and show ROI overlay on that image
- Edit a single ROI against the bound image
- Split a single ROI into multiple ROI parts with `Knife` and `Seed Split`
- Run cleanup tools such as `Keep Largest Part`, `Remove Small Islands`, `Fill Holes`, `Expand`, and `Shrink`
- Import from and export to the ROI Manager
- Convert between folder and ZIP representations
- Add ROI files directly into either folders or ROI ZIP archives
- Measure selected ROI files with ImageJ's standard measurement settings
- Measure grouped ROI sets with persistent group-measurement settings
- Undo and redo file/tree operations in the current ROI Explorer session
- Undo and redo selection edits during ROI edit mode

## Build

Requirements:

- Java 8
- Maven
- Fiji/ImageJ 1.54p-compatible environment

Build the plugin jar:

```bash
mvn -q package
```

The built jar is generated as:

```text
target/ROI_Explorer_Fiji.jar
```

## Install

Copy the built jar into your Fiji `plugins` directory, then restart Fiji.

## Basic workflow

### Open ROI Explorer

Launch `Plugins > ROI Explorer`.

If an image is already active when the window opens, ROI Explorer automatically binds to that image.

### Bind to an image

If no image is bound yet, open an image in Fiji and use `Bind` from the ROI Explorer window.

When bound, visible ROI files in the current view are drawn as an overlay on the image. Projection settings for `Z`, `C`, and `T` affect which ROI are shown.

### Browse and organize ROI files

- Double-click an ROI to start editing it
- Double-click a folder or ZIP to expand or collapse it
- Drag and drop ROI files and folders to reorganize the tree
- ROI files can be dropped into ROI ZIP archives, but folders, ZIP archives, and mixed selections cannot be dropped into a ZIP
- Use `More` for duplicate, ZIP/unZIP, ROI Manager interop, group measurements, and additional actions

### Edit a single ROI

Select one ROI and click `Edit`.

Edit mode uses the image's active selection. During edit mode:

- `Save` writes the edited ROI back to disk
- `Cancel` restores the original ROI
- `Undo` and `Redo` track selection changes within the edit session
- Cleanup tools operate on the current selection

### Split a single ROI

Select one ROI and open `Split Tools`.

Split mode is separate from edit mode. It treats the selected ROI file itself as the input and previews split results on the bound image.

Current split tools:

- `Knife`
- `Seed Split`

After previewing the result:

- `Save Split Results` writes the resulting ROI parts next to the original ROI, either in the same folder or in the same ROI ZIP archive
- By default the original ROI is kept
- `Replace original` can be enabled explicitly when you want the original ROI removed
- `Cancel` leaves the original ROI unchanged

### Measure ROI

`Measure ROI` follows ImageJ's normal measurement workflow:

- configure measurement items with ImageJ's standard `Set Measurements...`
- run `Measure ROI` from ROI Explorer to measure the selected ROI files

### Group Measure

`Group Measure` uses ROI Explorer's own persistent group-measurement settings.

Use `Set Group Measurements...` once, then run `Group Measure` repeatedly with the saved configuration.

Available group metrics include:

- ROI count and area summaries
- 3D volume and surface area
- sphericity
- intensity summaries
- centroid coordinates
- farthest-pair distance and endpoint coordinates
- 2D long-axis and short-axis lengths

Length-based output columns use the image calibration unit from metadata, not a fixed `um` label.

## Undo and redo

ROI Explorer currently has two undo/redo scopes:

- Main session history
  - file and tree operations such as add, delete, rename, move, duplicate, ZIP/unZIP, save, and split-save
- Edit-session history
  - selection changes during ROI edit mode only

Main history is session-local and is cleared when ROI Explorer is closed.

Edit and split tracking are also session-local. ROI Explorer does not assign permanent IDs to all nodes; it only tracks the currently edited ROI while an edit or split session is active.

## Notes

- Visibility is session state only. `Hide`, `Show`, and `Toggle Visibility` do not write custom metadata into ROI files.
- Group-measurement results depend on the bound image calibration and intensity data.
- Some keyboard shortcuts may overlap with existing ImageJ shortcuts when the bound image window is focused.

## License

This project is distributed under the MIT License. See [LICENSE](./LICENSE).

Third-party dependencies remain subject to their own licenses.

## Project status

This repository is still under active development. The current implementation is already usable for ROI browsing, editing, splitting, and measurement, but behavior and UI details may continue to change.

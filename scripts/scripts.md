This is a folder with scripts related to the backup and restore logs that would be used for the manipulating your cell logs.

There are scripts currently for merging cell logs (perhaps from different phones) and for converting CellMapper logs to CellInfo logs.

## Merge

## CellMapper Convert
To convert multiple files, use `cmd /r dir /s /b | clip` to get all file paths in a folder copied to your clipboard.

Still working on consistency between these two scripts and the app notably doesn't support multiple bands with same cellid in CellMapper like I have made these scripts to produce if is the case. (Recording behavior within the app still only records 1 band per cell)
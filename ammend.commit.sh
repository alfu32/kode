#!/bin/bash

git filter-repo --force \
  --email-callback '
def callback(email):
    return b"ioan.alferaru@gmail.com"
' \
  --name-callback '
def callback(name):
    return b"alfu32"
'